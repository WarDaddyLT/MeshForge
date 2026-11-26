package dev.meshforge.fieldflasher;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class FirmwareLibrary {
    static final int EXPORT_LIMIT = 4 * 1024 * 1024;
    static final int APP_START = 0x10000;
    static final int APP_LENGTH = 0x140000;
    private static final Set<String> ASSET_HOSTS = Set.of("github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com");
    private final DiagnosticLog log;
    private final File directory;
    private final JSONObject layout;
    private final CuratedFirmwareCache cache;

    record Image(byte[] bytes, JSONObject details) {}

    FirmwareLibrary(Context context, DiagnosticLog log) throws Exception {
        this.log = log;
        directory = new File(context.getFilesDir(), "firmware");
        if (!directory.isDirectory() && !directory.mkdir()) throw new IOException("Cannot open firmware cache");
        cache = new CuratedFirmwareCache(directory, log);
        try (var input = context.getAssets().open("m5-layout.json")) {
            layout = new JSONObject(new String(limited(input, 8192), StandardCharsets.UTF_8));
        }
    }

    static byte[] limited(InputStream input, int limit) throws IOException {
        return limited(input, limit, Long.MAX_VALUE);
    }

    private static byte[] limited(InputStream input, int limit, long deadline) throws IOException {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        for (int n; (n = input.read(buffer)) != -1;) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Download time limit exceeded");
            if (n > limit - output.size()) throw new IOException("Response exceeds byte limit " + limit);
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    static String digest(String type, byte[] data) throws Exception {
        StringBuilder output = new StringBuilder();
        for (byte b : MessageDigest.getInstance(type).digest(data)) {
            output.append(String.format(Locale.ROOT, "%02x", b & 255));
        }
        return output.toString();
    }

    static boolean safeFirmwareName(String name) {
        return name != null && name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} ._()+-]{0,219}");
    }

    private static void validateEspHeader(byte[] data) throws IOException {
        if (data.length < 288 || (data[0] & 255) != 0xe9 || data[1] < 1 || data[1] > 16 ||
                ((data[12] & 255) != 9 && data[12] != 0) || data[13] != 0 ||
                (data[32] & 255) != 0x32 || (data[33] & 255) != 0x54 ||
                (data[34] & 255) != 0xcd || (data[35] & 255) != 0xab) {
            throw new IOException("Expected an ESP32 or ESP32-S3 application image");
        }
    }

    private void validate(byte[] data, JSONObject details) throws Exception {
        validateEspHeader(data);
        if ((data[12] & 255) != details.optInt("chipId", 9) ||
                data.length > details.optInt("appLength", APP_LENGTH) || data.length != details.getInt("size") || !digest("SHA-256", data).equals(details.getString("sha256")) ||
                !digest("MD5", data).equals(details.getString("md5"))) {
            throw new IOException("Firmware digest check failed");
        }
        log.add("image", "Validated " + details.getString("tag") + " bytes=" + data.length +
                " sha256=" + details.getString("sha256"));
    }

    JSONObject layoutDetails() throws JSONException {
        return new JSONObject(layout.toString());
    }

    synchronized JSONArray inventory() throws Exception {
        return cache.inventory();
    }

    synchronized void removeCached(String key) throws Exception {
        cache.remove(key);
    }

    synchronized Image findCached(boolean direct, java.util.function.Predicate<JSONObject> matches) throws Exception {
        var entries = cache.inventory();
        for (int i = 0; i < entries.length(); i++) {
            var details = entries.getJSONObject(i);
            if (details.optBoolean("direct") != direct || !matches.test(details)) continue;
            try {
                return readCached(details, direct);
            } catch (IOException error) {
                log.add("firmware.cache", "Skipping invalid saved firmware: " + error.getMessage());
            }
        }
        return null;
    }

    synchronized Image load() throws Exception {
        var image = findCached(true, entry -> true);
        if (image == null) throw new FileNotFoundException("No direct firmware saved");
        return image;
    }

    private Image readCached(JSONObject details, boolean direct) throws Exception {
        byte[] bytes;
        try (var image = new FileInputStream(cache.blob(details))) {
            bytes = limited(image, EXPORT_LIMIT);
        }
        if (direct) {
            validate(bytes, details);
            if (!details.optBoolean("derivedLayout")) {
                var plan = layoutDetails();
                for (var fields = plan.keys(); fields.hasNext();) {
                    String field = fields.next();
                    details.put(field, plan.get(field));
                }
            }
        } else {
            validateExport(bytes, details);
        }
        return new Image(bytes, details);
    }

    static JSONObject uniqueAsset(JSONObject release, Pattern rule) throws Exception {
        JSONArray assets = release.getJSONArray("assets");
        JSONObject match = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!rule.matcher(asset.getString("name")).matches()) continue;
            if (match != null) throw new IOException("Several release assets match this build");
            match = asset;
        }
        return match;
    }

    byte[] fetchAsset(String repository, String tag, JSONObject asset, int maximum) throws Exception {
        return fetchAsset(repository, tag, asset, maximum, true);
    }

    byte[] fetchRepositoryAsset(String repository, String tag, JSONObject asset, int maximum) throws Exception {
        return fetchAsset(repository, tag, asset, maximum, false);
    }

    private byte[] fetchAsset(String repository, String tag, JSONObject asset, int maximum,
                              boolean requirePublishedDigest) throws Exception {
        String name = asset.getString("name");
        int size = asset.getInt("size");
        String hash = asset.optString("digest");
        String url = "https://github.com/" + repository + "/releases/download/" + tag + "/" + name;
        boolean hasDigest = hash.matches("sha256:[0-9a-f]{64}");
        if (!MeshCoreCatalogue.safeName(name) || !url.equals(asset.getString("browser_download_url")) ||
                requirePublishedDigest && !hasDigest || !hash.isEmpty() && !hasDigest || size <= 0 || size > maximum) {
            throw new IOException("Release asset URL, SHA-256 or size failed validation");
        }
        byte[] bytes = request(url, ASSET_HOSTS, size);
        if (bytes.length != size || hasDigest && !hash.equals("sha256:" + digest("SHA-256", bytes))) {
            throw new IOException("Published SHA-256 or size does not match the download");
        }
        return bytes;
    }

    synchronized JSONObject fetch(String source, String board, String role, JSONObject option) throws Exception {
        String tag = option.getString("tag");
        String name = option.getString("name");
        String repository = MeshCoreCatalogue.repository(source);
        boolean uf2 = name.endsWith(".uf2");
        boolean direct = !option.getString("appTag").isEmpty();
        boolean derived = direct && source.equals("official") && option.optBoolean("derivedLayout");
        if (direct && !derived && !MeshCoreCatalogue.supportsDirectUpdate(board, source, role)) {
            throw new IOException("Direct flashing is unavailable for this board and firmware role.");
        }
        boolean exportOnly = option.optBoolean("exportOnly");
        JSONObject release = new JSONObject(new String(request(
                "https://api.github.com/repos/" + repository + "/releases/tags/" + tag,
                Set.of("api.github.com"), 2 * 1024 * 1024), StandardCharsets.UTF_8));
        if (!tag.equals(release.getString("tag_name")) || release.getBoolean("draft") ||
                release.getBoolean("prerelease")) throw new IOException("Expected a stable release");

        JSONObject asset = uniqueAsset(release, Pattern.compile(Pattern.quote(name)));
        if (asset == null) throw new IOException("Selected image is unavailable in the published release");
        byte[] image = direct ? reusableApp(asset) : null;
        if (image == null) image = fetchAsset(repository, tag, asset, direct && !derived ? APP_LENGTH : EXPORT_LIMIT);
        String sha = asset.getString("digest").substring(7);
        int size = image.length;
        var details = new JSONObject().put("tag", tag).put("name", name).put("size", size)
                .put("assetId", asset.getLong("id")).put("sha256", sha).put("source", source)
                .put("repository", repository).put("board", board).put("role", role).put("direct", direct).put("exportOnly", exportOnly);
        if (!direct) {
            if (!exportOnly) {
                if (uf2) validateUf2(image, MeshCoreCatalogue.uf2Start(board));
                else validateEspHeader(image);
            }
            cache.store(details, image);
            log.add("download", "Verified download digest board=" + board + " source=" + repository +
                    " asset=" + asset.getLong("id") + " sha256=" + sha);
            return details;
        }
        if (derived || source.equals("save") || !role.equals("companionBle")) {
            String mergedName = name.substring(0, name.length() - 4) + "-merged.bin";
            JSONObject mergedAsset = uniqueAsset(release, Pattern.compile(Pattern.quote(mergedName)));
            if (mergedAsset == null) throw new IOException("Matching published partition layout is unavailable");
            byte[] merged = fetchAsset(repository, tag, mergedAsset, 4 * 1024 * 1024);
            if (derived) {
                var verified = EspApplicationPlan.derive(image, merged);
                byte[] erased = new byte[verified.otaLength()];
                Arrays.fill(erased, (byte) 0xff);
                details.put("derivedLayout", true).put("chipId", verified.chipId())
                        .put("appStart", verified.appStart()).put("appLength", verified.appLength())
                        .put("minimumFlash", verified.minimumFlash())
                        .put("partitionOffset", EspApplicationPlan.TABLE_OFFSET)
                        .put("partitionLength", EspApplicationPlan.TABLE_LENGTH)
                        .put("partitionMd5", digest("MD5", verified.table()))
                        .put("otaStart", verified.otaStart()).put("otaLength", verified.otaLength())
                        .put("otaEmptyMd5", digest("MD5", erased));
            } else {
                int partitionEnd = 0x8000 + layout.getInt("partitionLength");
                if (merged.length < APP_START + size ||
                        !digest("MD5", Arrays.copyOfRange(merged, 0x8000, partitionEnd))
                                .equals(layout.getString("partitionMd5")) ||
                        !Arrays.equals(image, 0, size, merged, APP_START, APP_START + size)) {
                    throw new IOException("The published app image does not match the approved partition layout for the selected board.");
                }
            }
            log.add("image", "Matched app bytes and partition table against the published merged image role=" + role);
        }
        details.put("md5", digest("MD5", image));
        validate(image, details);
        cache.store(details, image);
        log.add("download", "Committed asset=" + asset.getLong("id") + " size=" + size + " sha256=" + sha);
        return details;
    }

    private byte[] reusableApp(JSONObject asset) {
        try {
            var exported = findCached(false, details ->
                    asset.optString("digest").equals("sha256:" + details.optString("sha256")));
            if (exported != null && exported.bytes().length <= APP_LENGTH && exported.bytes().length == asset.getInt("size")) {
                log.add("image", "Reusing the verified offline app file");
                return exported.bytes();
            }
        } catch (Exception ignored) {
            // The app file can still be acquired and verified from the selected release.
        }
        return null;
    }

    static void validateUf2(byte[] image, int start) throws IOException {
        if (image.length == 0 || image.length % 512 != 0 || start == 0) {
            throw new IOException("Expected an approved application UF2");
        }
        var blocks = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        int count = image.length / 512;
        for (int block = 0; block < count; block++) {
            int at = block * 512;
            if (blocks.getInt(at) != 0x0a324655 || blocks.getInt(at + 4) != 0x9e5d5157 ||
                    blocks.getInt(at + 8) != 0x2000 || blocks.getInt(at + 12) != start + block * 256 ||
                    blocks.getInt(at + 16) != 256 || blocks.getInt(at + 20) != block ||
                    blocks.getInt(at + 24) != count || blocks.getInt(at + 28) != 0xada52840 ||
                    blocks.getInt(at + 508) != 0x0ab16f30 || start + (block + 1) * 256 > 0xd4000) {
                throw new IOException("Unexpected UF2 block or address at block " + block);
            }
        }
    }

    synchronized Image loadExport() throws Exception {
        var image = findCached(false, entry -> true);
        if (image == null) throw new FileNotFoundException("No exportable firmware saved");
        return image;
    }

    private static void validateExport(byte[] bytes, JSONObject details) throws Exception {
        if (bytes.length != details.getInt("size") || !details.getString("sha256").equals(digest("SHA-256", bytes))) {
            throw new IOException("Cached download digest check failed");
        }
        String name = details.getString("name");
        if (!MeshCoreCatalogue.safeName(name)) throw new IOException("Invalid download filename");
        if (!details.optBoolean("exportOnly")) {
            if (name.endsWith(".uf2")) validateUf2(bytes, MeshCoreCatalogue.uf2Start(details.getString("board")));
            else validateEspHeader(bytes);
        }
    }

    File cachedUf2(String sha, long size) {
        if (!sha.matches("[0-9a-f]{64}")) return null;
        File file = new File(directory, "export-" + sha + ".img");
        return file.isFile() && size > 0 && file.length() == size ? file : null;
    }

    static void atomic(File destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParentFile().toPath(), "partial-", ".tmp");
        try {
            try (var output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    byte[] request(String address, Set<String> hosts, int limit) throws Exception {
        URI location = URI.create(address);
        long started = android.os.SystemClock.elapsedRealtime();
        for (int hop = 0; hop <= 3; hop++) {
            if (!"https".equals(location.getScheme()) || !hosts.contains(location.getHost()) ||
                    location.getUserInfo() != null || location.getPort() != -1) {
                throw new IOException("Download redirected outside approved HTTPS origins");
            }
            var connection = (HttpURLConnection) location.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("User-Agent", "MeshForge-Field-Flasher/0.1.16");
            try {
                int code = connection.getResponseCode();
                long announced = connection.getContentLengthLong();
                log.add("https", "host=" + location.getHost() + " hop=" + hop + " status=" + code +
                        " contentLength=" + announced);
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || next.length() > 4096) throw new IOException("Invalid redirect");
                    location = location.resolve(next);
                    continue;
                }
                if (code != 200 || announced > limit) throw new IOException("HTTP " + code + ", or content too large");
                try (var input = connection.getInputStream()) {
                    byte[] bytes = limited(input, limit, started + 60_000);
                    log.add("https", "Read " + bytes.length + " bytes elapsedMs=" +
                            (android.os.SystemClock.elapsedRealtime() - started));
                    return bytes;
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}
