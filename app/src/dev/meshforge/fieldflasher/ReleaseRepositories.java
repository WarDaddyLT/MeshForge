package dev.meshforge.fieldflasher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

final class ReleaseRepositories {
    private static final Set<String> API_HOST = Set.of("api.github.com");
    private static final int MAX_LINKS = 24;
    private static final long FILE_CACHE_LIMIT = 64L * 1024 * 1024;
    private static final int INDEX_LIMIT = 2 * 1024 * 1024;
    private final SharedPreferences preferences;
    private final File directory;

    record Link(String owner, String repo, String tag) {
        String url() {
            return "https://github.com/" + owner + "/" + repo + "/releases" +
                    (tag.isEmpty() ? "" : "/tag/" + tag);
        }

        String repository() { return owner + "/" + repo; }
    }

    ReleaseRepositories(Context context) {
        preferences = context.getSharedPreferences("release-repositories", Context.MODE_PRIVATE);
        directory = new File(context.getFilesDir(), "repository-downloads");
    }

    static Link parse(String raw) {
        if (raw == null || raw.length() > 400) throw invalid();
        URI uri;
        try { uri = URI.create(raw.trim()); }
        catch (IllegalArgumentException error) { throw invalid(); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) ||
                uri.getUserInfo() != null || uri.getPort() != -1) throw invalid();
        String[] parts = uri.getRawPath().replaceAll("/+$", "").split("/", -1);
        if (parts.length < 3 || !parts[1].matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") ||
                !parts[2].matches("[A-Za-z0-9_.-]{1,100}") || parts[2].equals(".") ||
                parts[2].equals("..")) throw invalid();
        if (parts.length == 3 || (parts.length == 4 && parts[3].equals("releases")) ||
                (parts.length == 5 && parts[3].equals("releases") && parts[4].equals("latest"))) {
            return new Link(parts[1], parts[2], "");
        }
        if (parts.length == 6 && parts[3].equals("releases") &&
                (parts[4].equals("tag") || parts[4].equals("expanded_assets")) &&
                parts[5].matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}")) {
            return new Link(parts[1], parts[2], parts[5]);
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Use a public https://github.com repository or release URL.");
    }

    private static JSONObject item(Link link, boolean bootloader, boolean builtIn) throws JSONException {
        return new JSONObject().put("url", link.url()).put("bootloader", bootloader).put("builtIn", builtIn);
    }

    private JSONArray saved() throws JSONException {
        return new JSONArray(preferences.getString("links", "[]"));
    }

    JSONArray links() throws JSONException {
        var list = new JSONArray();
        list.put(item(parse("https://github.com/meshcore-dev/MeshCore"), false, true));
        list.put(item(parse("https://github.com/aigarslv/SaveMeshcore"), false, true));
        list.put(item(parse("https://github.com/MarekZegare4/MeshCore-Solo"), false, true));
        list.put(item(parse("https://github.com/oltaco/Adafruit_nRF52_Bootloader_OTAFIX"), true, true));
        var saved = saved();
        for (int i = 0; i < saved.length(); i++) list.put(saved.getJSONObject(i));
        return list;
    }

    void add(String raw, boolean bootloader) throws Exception {
        Link link = parse(raw);
        var all = links();
        for (int i = 0; i < all.length(); i++) {
            var entry = all.getJSONObject(i);
            if (link.url().equalsIgnoreCase(entry.getString("url"))) return;
        }
        var list = saved();
        if (list.length() >= MAX_LINKS) throw new IOException("Remove a saved repository before adding another.");
        list.put(item(link, bootloader, false));
        preferences.edit().putString("links", list.toString()).apply();
    }

    void remove(String raw) throws Exception {
        Link link = parse(raw);
        String url = link.url();
        var list = saved();
        var remaining = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            var entry = list.getJSONObject(i);
            if (!url.equalsIgnoreCase(entry.getString("url"))) remaining.put(entry);
            else Files.deleteIfExists(index(parse(entry.getString("url"))).toPath());
        }
        preferences.edit().putString("links", remaining.toString()).apply();
    }

    private File index(Link link) throws Exception {
        String key = FirmwareLibrary.digest("SHA-256", link.url().getBytes(StandardCharsets.UTF_8));
        return new File(directory, key + ".json");
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdir()) throw new IOException("Repository cache is unavailable.");
    }

    JSONObject assets(String raw, boolean refresh, FirmwareLibrary library) throws Exception {
        Link link = parse(raw);
        File index = index(link);
        JSONArray files;
        String notice = "";
        try {
            if (refresh || !index.isFile()) {
                files = publishedAssets(link, library);
                if (index.isFile()) retainOfflineEntries(files, readIndex(index), library);
                byte[] encoded = files.toString().getBytes(StandardCharsets.UTF_8);
                if (encoded.length > INDEX_LIMIT) throw new IOException("The release list exceeds the offline cache limit.");
                ensureDirectory();
                FirmwareLibrary.atomic(index, encoded);
            } else {
                files = readIndex(index);
                notice = "Saved list. Refresh to check for new releases.";
            }
        } catch (Exception error) {
            if (!index.isFile() || !refresh) throw error;
            files = readIndex(index);
            notice = "Refresh failed. Showing the saved list.";
        }
        for (int i = 0; i < files.length(); i++) {
            JSONObject asset = files.getJSONObject(i);
            String digest = asset.getString("digest");
            if (digest.isEmpty()) continue;
            File file = localFile(digest.substring(7));
            long size = asset.getLong("size");
            boolean savedHere = file.isFile() && file.length() == size;
            asset.put("downloaded", savedHere || library.cachedUf2(digest.substring(7), size) != null)
                    .put("removable", savedHere);
        }
        return new JSONObject().put("files", files).put("notice", notice);
    }

    private void retainOfflineEntries(JSONArray published, JSONArray previous, FirmwareLibrary library) throws Exception {
        var current = new java.util.HashSet<String>();
        for (int i = 0; i < published.length(); i++) {
            var file = published.getJSONObject(i);
            current.add(file.getString("id") + ":" + file.getString("digest"));
        }
        for (int i = 0; i < previous.length(); i++) {
            var asset = previous.getJSONObject(i);
            String digest = asset.getString("digest");
            if (digest.isEmpty() || current.contains(asset.getString("id") + ":" + digest)) continue;
            String sha = digest.substring(7);
            File file = localFile(sha);
            long size = asset.getLong("size");
            if ((file.isFile() && file.length() == size) || library.cachedUf2(sha, size) != null) {
                published.put(asset);
            }
        }
    }

    private static JSONArray readIndex(File index) throws Exception {
        try (var stream = new FileInputStream(index)) {
            return new JSONArray(new String(FirmwareLibrary.limited(stream, INDEX_LIMIT), StandardCharsets.UTF_8));
        }
    }

    private JSONArray publishedAssets(Link link, FirmwareLibrary library) throws Exception {
        String endpoint = "https://api.github.com/repos/" + link.repository() + "/releases";
        if (link.tag().isEmpty()) {
            var releases = new JSONArray(new String(library.request(endpoint + "?per_page=6", API_HOST,
                    5 * 1024 * 1024), StandardCharsets.UTF_8));
            var result = new JSONArray();
            for (int i = 0; i < releases.length(); i++) append(result, releases.getJSONObject(i), link, false);
            return result;
        }
        var release = release(link, link.tag(), library);
        var result = new JSONArray();
        append(result, release, link, true);
        return result;
    }

    private static JSONObject release(Link link, String tag, FirmwareLibrary library) throws Exception {
        if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}") ||
                (!link.tag().isEmpty() && !tag.equals(link.tag()))) throw invalid();
        return new JSONObject(new String(library.request("https://api.github.com/repos/" + link.repository() +
                "/releases/tags/" + tag, API_HOST, 2 * 1024 * 1024), StandardCharsets.UTF_8));
    }

    private static boolean isUf2Asset(JSONObject asset) {
        String name = asset.optString("name");
        return MeshCoreCatalogue.safeName(name) && name.toLowerCase(Locale.ROOT).endsWith(".uf2") &&
                asset.optLong("size") > 0 && asset.optLong("size") <= FirmwareLibrary.EXPORT_LIMIT;
    }

    private static void append(JSONArray result, JSONObject release, Link link, boolean explicit) throws JSONException {
        String tag = release.optString("tag_name");
        if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}") || release.optBoolean("draft") ||
                (!explicit && release.optBoolean("prerelease")) || (!link.tag().isEmpty() && !tag.equals(link.tag()))) return;
        var assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            var asset = assets.getJSONObject(i);
            if (!isUf2Asset(asset)) continue;
            String digest = asset.optString("digest");
            result.put(new JSONObject().put("name", asset.getString("name")).put("tag", tag)
                    .put("id", Long.toString(asset.getLong("id"))).put("size", asset.getLong("size"))
                    .put("digest", digest.matches("sha256:[0-9a-f]{64}") ? digest : ""));
        }
    }

    JSONObject download(String raw, String tag, long id, String digest, FirmwareLibrary library) throws Exception {
        Link link = parse(raw);
        if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IOException("The asset has no published SHA-256.");
        var release = release(link, tag, library);
        if (!tag.equals(release.optString("tag_name")) || release.optBoolean("draft") ||
                (link.tag().isEmpty() && release.optBoolean("prerelease"))) throw new IOException("Release unavailable.");
        var assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            var asset = assets.getJSONObject(i);
            if (id != asset.optLong("id") || !digest.equals(asset.optString("digest")) || !isUf2Asset(asset)) continue;
            String sha = digest.substring(7);
            File file = localFile(sha);
            ensureDirectory();
            long used = 0;
            var entries = directory.listFiles();
            if (entries == null) throw new IOException("Repository cache is unavailable.");
            for (File entry : entries) {
                if (entry.getName().endsWith(".uf2") && !entry.equals(file)) used += entry.length();
            }
            if (asset.getLong("size") > FILE_CACHE_LIMIT - used) {
                throw new IOException("Repository storage is full. Remove an offline copy to free space.");
            }
            byte[] data = library.fetchAsset(link.repository(), tag, asset, FirmwareLibrary.EXPORT_LIMIT);
            FirmwareLibrary.atomic(file, data);
            return new JSONObject().put("name", asset.getString("name"))
                    .put("sha256", sha).put("size", data.length);
        }
        throw new IOException("This published asset has changed or is unavailable.");
    }

    private File localFile(String sha) throws IOException {
        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("Invalid file checksum.");
        return new File(directory, sha + ".uf2");
    }

    void removeLocal(String sha) throws IOException {
        Files.deleteIfExists(localFile(sha).toPath());
    }

    FirmwareLibrary.Image loadLocal(String name, String sha, long size, FirmwareLibrary library) throws Exception {
        if (!MeshCoreCatalogue.safeName(name) || !name.toLowerCase(Locale.ROOT).endsWith(".uf2")) {
            throw new IOException("Invalid UF2 file name.");
        }
        File file = localFile(sha);
        if (!file.isFile()) file = library.cachedUf2(sha, size);
        if (file == null) throw new IOException("The offline copy is unavailable.");
        byte[] bytes;
        try (var input = new FileInputStream(file)) {
            bytes = FirmwareLibrary.limited(input, FirmwareLibrary.EXPORT_LIMIT);
        }
        if (bytes.length != size || !sha.equals(FirmwareLibrary.digest("SHA-256", bytes))) {
            throw new IOException("The offline copy failed checksum verification.");
        }
        return new FirmwareLibrary.Image(bytes, new JSONObject().put("name", name).put("sha256", sha).put("board", ""));
    }

}
