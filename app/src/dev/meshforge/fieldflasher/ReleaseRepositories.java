package dev.meshforge.fieldflasher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.LinkedHashMap;
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
        var item = new JSONObject().put("url", link.url()).put("bootloader", bootloader).put("builtIn", builtIn);
        if (!bootloader && link.tag().isEmpty()) {
            String source = switch (link.repository().toLowerCase(Locale.ROOT)) {
                case "meshcore-dev/meshcore" -> "official";
                case "aigarslv/savemeshcore" -> "save";
                case "marekzegare4/meshcore-solo" -> "solo";
                default -> "";
            };
            if (!source.isEmpty()) item.put("source", source);
        }
        return item;
    }

    private JSONArray saved() throws JSONException {
        return new JSONArray(preferences.getString("links", "[]"));
    }

    JSONArray links() throws JSONException {
        var links = new LinkedHashMap<String, JSONObject>();
        var removed = preferences.getStringSet("removed-defaults", Set.of());
        for (String url : new String[]{"https://github.com/meshcore-dev/MeshCore",
                "https://github.com/aigarslv/SaveMeshcore", "https://github.com/MarekZegare4/MeshCore-Solo",
                "https://github.com/WarDaddyLT/Meshcore-Solo-Zygis", "https://github.com/WarDaddyLT/MeshCore-Hardened",
                "https://github.com/oltaco/Adafruit_nRF52_Bootloader_OTAFIX"}) {
            Link link = parse(url);
            String key = link.url().toLowerCase(Locale.ROOT);
            if (!removed.contains(key)) {
                links.put(key, item(link, link.repo().equals("Adafruit_nRF52_Bootloader_OTAFIX"), true));
            }
        }
        var saved = saved();
        for (int i = 0; i < saved.length(); i++) {
            var entry = saved.getJSONObject(i);
            Link link = parse(entry.getString("url"));
            String key = link.url().toLowerCase(Locale.ROOT);
            var existing = links.get(key);
            boolean builtIn = existing != null && existing.optBoolean("builtIn");
            links.put(key, item(link, entry.optBoolean("bootloader"), builtIn));
        }
        return new JSONArray(links.values());
    }

    private Link configured(String raw) throws Exception {
        Link link = parse(raw);
        var list = links();
        for (int i = 0; i < list.length(); i++) {
            Link saved = parse(list.getJSONObject(i).getString("url"));
            if (saved.url().equalsIgnoreCase(link.url())) return saved;
        }
        throw new IOException("This repository is no longer configured.");
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
        }
        var removed = new java.util.HashSet<>(preferences.getStringSet("removed-defaults", Set.of()));
        var configured = links();
        for (int i = 0; i < configured.length(); i++) {
            JSONObject entry = configured.getJSONObject(i);
            if (entry.optBoolean("builtIn") && url.equalsIgnoreCase(entry.getString("url"))) {
                removed.add(url.toLowerCase(Locale.ROOT));
            }
        }
        File removedIndex = index(link);
        if (removedIndex.isFile()) {
            // Preserve the names of older offline files before discarding this repository's index.
            JSONArray downloads = downloads();
            for (int i = 0; i < downloads.length(); i++) {
                JSONObject details = downloads.getJSONObject(i);
                File metadata = downloadDetails(details.getString("sha256"));
                if (!metadata.isFile() || link.repository().equalsIgnoreCase(details.optString("sourceLabel"))) {
                    FirmwareLibrary.atomic(metadata, details.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            Files.deleteIfExists(removedIndex.toPath());
        }
        preferences.edit().putString("links", remaining.toString()).putStringSet("removed-defaults", removed).apply();
    }

    private File index(Link link) throws Exception {
        String key = FirmwareLibrary.digest("SHA-256", link.url().getBytes(StandardCharsets.UTF_8));
        return new File(directory, key + ".json");
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdir()) throw new IOException("Repository cache is unavailable.");
    }

    JSONObject assets(String raw, boolean refresh, FirmwareLibrary library) throws Exception {
        Link link = configured(raw);
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
            var result = new JSONArray();
            for (int page = 1; ; page++) {
                var releases = new JSONArray(new String(library.request(endpoint + "?per_page=100&page=" + page,
                        API_HOST, 5 * 1024 * 1024), StandardCharsets.UTF_8));
                for (int i = 0; i < releases.length(); i++) append(result, releases.getJSONObject(i), link);
                if (releases.length() < 100) break;
                if (result.toString().getBytes(StandardCharsets.UTF_8).length > INDEX_LIMIT) {
                    throw new IOException("The release list exceeds the offline cache limit.");
                }
            }
            return result;
        }
        var release = release(link, link.tag(), library);
        var result = new JSONArray();
        append(result, release, link);
        return result;
    }

    private static JSONObject release(Link link, String tag, FirmwareLibrary library) throws Exception {
        if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}") ||
                (!link.tag().isEmpty() && !tag.equals(link.tag()))) throw invalid();
        return new JSONObject(new String(library.request("https://api.github.com/repos/" + link.repository() +
                "/releases/tags/" + tag, API_HOST, 2 * 1024 * 1024), StandardCharsets.UTF_8));
    }

    private static boolean isRepositoryAsset(JSONObject asset) {
        String name = asset.optString("name");
        return !name.isBlank() && name.length() <= 512 && asset.optLong("id") > 0 && asset.optLong("size", -1) >= 0;
    }

    private static boolean isDownloadableAsset(JSONObject asset) {
        String name = asset.optString("name");
        long size = asset.optLong("size", -1);
        return isRepositoryAsset(asset) && MeshCoreCatalogue.safeName(name) && size > 0 &&
                size <= FirmwareLibrary.EXPORT_LIMIT;
    }

    private static void append(JSONArray result, JSONObject release, Link link) throws JSONException {
        String tag = release.optString("tag_name");
        if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}") || release.optBoolean("draft") ||
                (!link.tag().isEmpty() && !tag.equals(link.tag()))) return;
        var assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            var asset = assets.getJSONObject(i);
            if (!isRepositoryAsset(asset)) continue;
            String digest = asset.optString("digest");
            result.put(new JSONObject().put("name", asset.getString("name")).put("tag", tag)
                    .put("releaseName", release.optString("name"))
                    .put("prerelease", release.optBoolean("prerelease"))
                    .put("publishedAt", release.optString("published_at"))
                    .put("id", Long.toString(asset.getLong("id"))).put("size", asset.getLong("size"))
                    .put("sourceLabel", link.repository())
                    .put("downloadable", isDownloadableAsset(asset))
                    .put("digest", digest.matches("sha256:[0-9a-f]{64}") ? digest : ""));
        }
    }

    JSONObject download(String raw, String tag, long id, String digest, FirmwareLibrary library) throws Exception {
        Link link = configured(raw);
        if (!digest.isEmpty() && !digest.matches("sha256:[0-9a-f]{64}")) throw new IOException("Invalid asset checksum.");
        var release = release(link, tag, library);
        if (!tag.equals(release.optString("tag_name")) || release.optBoolean("draft")) throw new IOException("Release unavailable.");
        var assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            var asset = assets.getJSONObject(i);
            String publishedDigest = asset.optString("digest");
            if (id != asset.optLong("id") || !digest.isEmpty() && !digest.equals(publishedDigest) ||
                    !isDownloadableAsset(asset)) continue;
            byte[] data = library.fetchRepositoryAsset(link.repository(), tag, asset, FirmwareLibrary.EXPORT_LIMIT);
            String sha = FirmwareLibrary.digest("SHA-256", data);
            File file = localFile(sha);
            ensureDirectory();
            long used = 0;
            var entries = directory.listFiles();
            if (entries == null) throw new IOException("Repository cache is unavailable.");
            for (File entry : entries) {
                if (entry.getName().matches("[0-9a-f]{64}\\.(asset|uf2)") && !entry.equals(file)) used += entry.length();
            }
            if (asset.getLong("size") > FILE_CACHE_LIMIT - used) {
                throw new IOException("Repository storage is full. Remove an offline copy to free space.");
            }
            var details = new JSONObject().put("name", asset.getString("name")).put("tag", tag)
                    .put("releaseName", release.optString("name")).put("prerelease", release.optBoolean("prerelease"))
                    .put("sourceLabel", link.repository()).put("sha256", sha).put("size", data.length)
                    .put("checksumPublished", publishedDigest.matches("sha256:[0-9a-f]{64}"));
            FirmwareLibrary.atomic(file, data);
            saveDetails(sha, details);
            return details;
        }
        throw new IOException("This published asset has changed or is unavailable.");
    }

    JSONObject importLocalFirmware(String name, byte[] bytes) throws Exception {
        if (!FirmwareLibrary.safeFirmwareName(name) ||
                !name.toLowerCase(Locale.ROOT).matches(".+\\.(uf2|bin|zip|hex)")) {
            throw new IOException("Choose a UF2, BIN, ZIP, or HEX firmware file.");
        }
        if (bytes.length == 0 || bytes.length > FirmwareLibrary.EXPORT_LIMIT) {
            throw new IOException("The selected firmware file is empty or too large.");
        }
        String sha = FirmwareLibrary.digest("SHA-256", bytes);
        File file = localFile(sha);
        ensureDirectory();
        long used = 0;
        var entries = directory.listFiles();
        if (entries == null) throw new IOException("Repository storage is unavailable.");
        for (File entry : entries) {
            if (entry.getName().matches("[0-9a-f]{64}\\.(asset|uf2)") && !entry.equals(file)) used += entry.length();
        }
        if (bytes.length > FILE_CACHE_LIMIT - used) {
            throw new IOException("Repository storage is full. Remove an offline copy to free space.");
        }
        var details = new JSONObject().put("name", name).put("tag", "Added locally")
                .put("sourceLabel", "Local files").put("local", true).put("sha256", sha).put("size", bytes.length);
        FirmwareLibrary.atomic(file, bytes);
        saveDetails(sha, details);
        return details;
    }

    private void saveDetails(String sha, JSONObject details) throws Exception {
        File metadata = downloadDetails(sha);
        if (metadata.isFile()) {
            try (var input = new FileInputStream(metadata)) {
                JSONObject previous = new JSONObject(new String(FirmwareLibrary.limited(input, 8192), StandardCharsets.UTF_8));
                if (previous.optBoolean("local")) return;
            } catch (Exception unreadable) { /* Replace incomplete or invalid metadata. */ }
        }
        FirmwareLibrary.atomic(metadata, details.toString().getBytes(StandardCharsets.UTF_8));
    }

    private File localFile(String sha) throws IOException {
        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("Invalid file checksum.");
        File legacy = new File(directory, sha + ".uf2");
        return legacy.isFile() ? legacy : new File(directory, sha + ".asset");
    }

    void removeLocal(String sha) throws IOException {
        Files.deleteIfExists(localFile(sha).toPath());
        Files.deleteIfExists(downloadDetails(sha).toPath());
    }

    private File downloadDetails(String sha) {
        return new File(directory, "download-" + sha + ".json");
    }

    JSONArray downloads() {
        var files = new LinkedHashMap<String, JSONObject>();
        var entries = directory.listFiles();
        if (entries == null) return new JSONArray();
        for (File entry : entries) {
            if (!entry.getName().matches("download-[0-9a-f]{64}\\.json")) continue;
            try (var input = new FileInputStream(entry)) {
                var details = new JSONObject(new String(FirmwareLibrary.limited(input, 8192), StandardCharsets.UTF_8));
                String sha = details.getString("sha256");
                if (localFile(sha).isFile() && localFile(sha).length() == details.getLong("size")) files.put(sha, details);
            } catch (Exception unreadable) { /* Ignore incomplete or evicted entries. */ }
        }
        // Recover the repository for older downloads from their release index.
        var sources = new LinkedHashMap<String, String>();
        try {
            JSONArray links = links();
            for (int i = 0; i < links.length(); i++) {
                Link link = parse(links.getJSONObject(i).getString("url"));
                sources.put(index(link).getName(), link.repository());
            }
        } catch (Exception unreadable) { /* Downloads can still be listed without the repository name. */ }
        for (File entry : entries) {
            if (!entry.getName().matches("[0-9a-f]{64}\\.json")) continue;
            try {
                var assets = readIndex(entry);
                for (int i = 0; i < assets.length(); i++) {
                    var asset = assets.getJSONObject(i);
                    String digest = asset.optString("digest");
                    if (!digest.matches("sha256:[0-9a-f]{64}")) continue;
                    String sha = digest.substring(7);
                    long size = asset.optLong("size");
                    String source = asset.optString("sourceLabel", sources.getOrDefault(entry.getName(), ""));
                    if (!files.containsKey(sha) && localFile(sha).isFile() && localFile(sha).length() == size) {
                        files.put(sha, new JSONObject().put("name", asset.getString("name")).put("tag", asset.getString("tag"))
                                .put("sha256", sha).put("size", size).put("sourceLabel", source));
                    } else if (files.containsKey(sha) && !source.isBlank()) {
                        JSONObject details = files.get(sha);
                        String existing = details.optString("sourceLabel");
                        if (existing.isBlank() || existing.equals("Repository download")) details.put("sourceLabel", source);
                    }
                }
            } catch (Exception unreadable) { /* Other cached files remain available. */ }
        }
        return new JSONArray(files.values());
    }

    FirmwareLibrary.Image loadLocal(String name, String sha, long size, FirmwareLibrary library) throws Exception {
        if (!FirmwareLibrary.safeFirmwareName(name) || !name.toLowerCase(Locale.ROOT).matches(".+\\.(uf2|bin|zip|hex)")) {
            throw new IOException("Invalid firmware file name.");
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
