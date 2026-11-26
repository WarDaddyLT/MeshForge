package dev.meshforge.fieldflasher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;

final class CuratedFirmwareCache {
    private static final long LIMIT = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 512;
    private final File directory;
    private final DiagnosticLog log;

    CuratedFirmwareCache(File directory, DiagnosticLog log) {
        this.directory = directory;
        this.log = log;
    }

    JSONArray inventory() throws Exception {
        migrate("release.json", true);
        migrate("export.json", false);
        return new JSONArray(entries());
    }

    JSONObject store(JSONObject details, byte[] image) throws Exception {
        var inventory = inventory();
        prune(entries());
        String key = key(details);
        File metadata = metadata(key);
        if (metadata.isFile()) {
            var existing = read(metadata);
            if (!existing.getString("sha256").equals(details.getString("sha256")) ||
                    existing.optLong("assetId") != details.optLong("assetId")) {
                throw new IOException("The published asset changed for this tag. The saved file was kept.");
            }
        } else if (inventory.length() >= MAX_ENTRIES) {
            throw new IOException("Firmware storage is full. Remove a saved file from Library.");
        }
        File blob = blob(details);
        long used = 0;
        for (File file : files()) {
            if (isBlob(file) && !file.equals(blob)) used += file.length();
        }
        if (image.length > LIMIT - used) throw new IOException("Firmware storage is full. Remove a saved file from Library.");
        FirmwareLibrary.atomic(blob, image);
        details.put("cacheKey", key);
        FirmwareLibrary.atomic(metadata, details.toString().getBytes(StandardCharsets.UTF_8));
        return details;
    }

    void remove(String key) throws Exception {
        Files.deleteIfExists(metadata(key).toPath());
        prune(entries());
    }

    File blob(JSONObject entry) throws Exception {
        String sha = entry.getString("sha256");
        if (!sha.matches("[a-f0-9]{64}")) throw new IOException("Invalid firmware checksum.");
        return new File(directory, entry.optBoolean("direct") ? sha + ".bin" : "export-" + sha + ".img");
    }

    private ArrayList<JSONObject> entries() throws IOException {
        var files = files();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
        var entries = new ArrayList<JSONObject>();
        for (File file : files) {
            if (!file.getName().matches("cached-[a-f0-9]{64}\\.json")) continue;
            try {
                JSONObject entry = read(file);
                if (metadata(entry.getString("cacheKey")).equals(file) &&
                        blob(entry).isFile() && blob(entry).length() == entry.getLong("size")) entries.add(entry);
            } catch (Exception error) {
                log.add("firmware.cache", "Skipped an invalid cache entry");
            }
        }
        return entries;
    }

    private void migrate(String filename, boolean direct) throws Exception {
        var previous = new File(directory, filename);
        if (!previous.isFile()) return;
        JSONObject details;
        try {
            details = read(previous).put("direct", direct);
            if (!blob(details).isFile() || blob(details).length() != details.getLong("size")) return;
        } catch (Exception error) {
            log.add("firmware.cache", "Skipped an invalid legacy cache entry");
            return;
        }
        var target = metadata(key(details));
        if (!target.isFile()) {
            details.put("cacheKey", key(details));
            FirmwareLibrary.atomic(target, details.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.deleteIfExists(previous.toPath());
    }

    private void prune(ArrayList<JSONObject> entries) throws Exception {
        var used = new HashSet<File>();
        for (JSONObject entry : entries) used.add(blob(entry));
        // Valid legacy metadata is migrated before pruning. Keep any legacy blobs
        // while an invalid legacy index still exists.
        if (new File(directory, "release.json").isFile() || new File(directory, "export.json").isFile()) return;
        for (File file : files()) if (isBlob(file) && !used.contains(file)) Files.deleteIfExists(file.toPath());
    }

    private File metadata(String key) throws IOException {
        if (!key.matches("[a-f0-9]{64}")) throw new IOException("Invalid cached firmware selection.");
        return new File(directory, "cached-" + key + ".json");
    }

    private static String key(JSONObject details) throws Exception {
        var identity = new JSONArray();
        for (String field : new String[]{"board", "source", "role", "tag", "name", "direct"}) {
            identity.put(details.optString(field));
        }
        return FirmwareLibrary.digest("SHA-256", identity.toString().getBytes(StandardCharsets.UTF_8));
    }

    private File[] files() throws IOException {
        var files = directory.listFiles();
        if (files == null) throw new IOException("Firmware storage is unavailable.");
        return files;
    }

    private static boolean isBlob(File file) {
        return file.getName().matches("(?:[a-f0-9]{64}\\.bin|export-[a-f0-9]{64}\\.img)");
    }

    private static JSONObject read(File file) throws Exception {
        try (var input = new FileInputStream(file)) {
            return new JSONObject(new String(FirmwareLibrary.limited(input, 8192), StandardCharsets.UTF_8));
        }
    }
}
