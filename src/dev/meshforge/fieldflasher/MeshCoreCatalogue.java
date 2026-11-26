package dev.meshforge.fieldflasher;

import android.content.Context;
import android.os.SystemClock;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class MeshCoreCatalogue {
    private static final String FEED = "https://flasher.meshcore.io/releases?repo=github";
    private final JSONArray boards;
    private final Map<String, JSONObject> boardByName = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    static final String M5 = "Elecrow ThinkNode M5";
    private static final String T_DECK = "LilyGo T-Deck";
    private static final String XIAO = "Seeed Studio Xiao S3 WIO";
    static final String RAK_1W = "RAK WisMesh 1W Booster (3401 + 13302)";
    private static final String WIO = "Seeed Studio Wio Tracker L1 Pro";
    private static final String WIO_EINK = "Seeed Studio Wio Tracker L1 EINK";
    private final Map<String, JSONArray> forkReleases = new LinkedHashMap<>();
    private final Map<String, Long> forkFetchedAt = new LinkedHashMap<>();
    private JSONArray releases;
    private long fetchedAt;

    MeshCoreCatalogue(Context context) throws Exception {
        try (var input = context.getAssets().open("meshcore-catalogue.json")) {
            boards = new JSONArray(new String(FirmwareLibrary.limited(input, 512 * 1024), StandardCharsets.UTF_8));
        }
        for (int i = 0; i < boards.length(); i++) {
            JSONObject board = boards.getJSONObject(i);
            String name = board.getString("name");
            String maker = board.optString("maker");
            board.put("uf2Dfu", "nrf52".equals(board.optString("type")) && uf2Start(name) != 0);
            board.put("dfuEntry", dfuInstructions(name));
            boardByName.put(name, board);
            addAlias(name, name);
            if (normalize(name).startsWith(normalize(maker) + " ")) {
                addAlias(normalize(name).substring(normalize(maker).length() + 1), name);
            }
        }
        addAlias("WioTrackerL1", WIO);
        addAlias("T-Echo-Lite", "LilyGo T-Echo Lite");
        addAlias("RAK 3401", RAK_1W);
        addAlias("RAK3401", RAK_1W);
        addAlias("WisCore RAK3401 Board", RAK_1W);
        addAlias("WisBlock RAK3401 Board", RAK_1W);
    }

    private static String normalize(String label) {
        return label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private void addAlias(String label, String board) {
        String key = normalize(label);
        if (!aliases.containsKey(key)) aliases.put(key, board);
        else if (!board.equals(aliases.get(key))) aliases.put(key, null);
    }

    String match(String label) {
        return aliases.get(normalize(label));
    }

    JSONArray boards() { return boards; }

    private JSONObject entry(String board) {
        JSONObject entry = boardByName.get(board);
        if (entry == null) throw new IllegalArgumentException("Choose a board from the MeshCore catalogue.");
        return entry;
    }

    String officialBoardUrl(String board) {
        String slug = entry(board).optString("name").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.]+", "-").replaceAll("^-|-$", "");
        return "https://flasher.meshcore.io/" + slug + "/";
    }

    boolean isNrf(String board) { return "nrf52".equals(entry(board).optString("type")); }

    private static String dfuInstructions(String board) {
        if (WIO.equals(board) || WIO_EINK.equals(board)) {
            return "Keep USB connected, turn the radio on and quickly press RST twice. " +
                    "The yellow LED should stay solid. Allow up to 15 seconds for the UF2 drive to appear.";
        }
        if (board.startsWith("LilyGo T-Echo")) {
            return "With the radio connected, double-press RESET within about 500 ms to enter UF2 mode.";
        }
        return "Use the board manufacturer's hardware sequence to enter UF2/DFU mode.";
    }

    String dfuEntry(String board) { return entry(board).optString("dfuEntry"); }

    JSONArray otaFixes(String board) {
        JSONArray fixes = entry(board).optJSONArray("otafix");
        return fixes == null ? new JSONArray() : fixes;
    }

    static int uf2Start(String board) {
        return switch (board) {
            case WIO, WIO_EINK -> 0x27000;
            case "LilyGo T-Echo", "LilyGo T-Echo Lite", RAK_1W -> 0x26000;
            default -> 0;
        };
    }

    static String repository(String source) {
        return switch (source) {
            case "official" -> "meshcore-dev/MeshCore";
            case "save" -> "aigarslv/SaveMeshcore";
            case "solo" -> "MarekZegare4/MeshCore-Solo";
            default -> throw new IllegalArgumentException("Unknown firmware source.");
        };
    }

    static boolean supportsDirectUpdate(String board, String source, String role) {
        if (!M5.equals(board)) return false;
        return switch (source) {
            case "official" -> Set.of("companionBle", "companionUsb", "repeater").contains(role);
            case "save" -> "repeater".equals(role);
            default -> false;
        };
    }

    synchronized JSONArray versions(String board, String role, String source, FirmwareLibrary library) throws Exception {
        String repo = repository(source);
        if (!board.isEmpty()) entry(board);
        if (source.equals("official")) return officialVersions(board, role, library);
        return forkVersions(board, role, source, repo, library);
    }

    private JSONArray officialVersions(String board, String role, FirmwareLibrary library) throws Exception {
        boolean direct = supportsDirectUpdate(board, "official", role);
        JSONArray result = new JSONArray();
        String targetType = "";
        Pattern targetFile = null;
        if (!board.isEmpty()) {
            boolean esp = "esp32".equals(entry(board).optString("type"));
            JSONArray roles = entry(board).getJSONArray("roles");
            JSONObject spec = null;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject option = roles.getJSONObject(i);
                if (role.equals(option.getString("id"))) {
                    spec = option.optJSONObject("release");
                    break;
                }
            }
            if (spec != null) {
                targetType = spec.getString("type");
                var formats = spec.getJSONObject("files");
                String rule = formats.optString(esp ? "flash-update" : "download");
                direct = direct || esp && !M5.equals(board) && formats.has("flash-wipe") && !rule.isEmpty();
                if (!rule.isEmpty()) {
                    // The published M5 repeater asset uses "Repeater" in releases with the lowercase catalogue rule.
                    int flags = M5.equals(board) && "repeater".equals(role) ? Pattern.CASE_INSENSITIVE : 0;
                    targetFile = Pattern.compile(rule, flags);
                }
            }
        }

        long now = SystemClock.elapsedRealtime();
        if (releases == null || now - fetchedAt >= 5 * 60_000) {
            releases = new JSONArray(new String(library.request(FEED, Set.of("flasher.meshcore.io"),
                    2 * 1024 * 1024), StandardCharsets.UTF_8));
            fetchedAt = SystemClock.elapsedRealtime();
        }
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            String type = release.optString("type");
            String version = release.optString("version");
            if (type.isEmpty() || !version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) continue;
            String tag = type + "-" + version;
            JSONArray files = release.getJSONArray("files");
            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.getJSONObject(j);
                String name = file.optString("name");
                if (!safeName(name) || !firmwareName(name) ||
                        !file.optString("url").equals("/releases/download/" + tag + "/" + name)) continue;
                boolean target = type.equals(targetType) && targetFile != null && targetFile.matcher(name).matches();
                JSONObject candidate = option(tag, version, name, target && direct, !target);
                if (target && direct && !M5.equals(board)) candidate.put("derivedLayout", true);
                result.put(candidate);
            }
        }
        return result;
    }

    private JSONArray forkVersions(String board, String role, String source, String repo,
                                   FirmwareLibrary library) throws Exception {
        JSONArray result = new JSONArray();
        boolean save = source.equals("save") && role.equals("repeater");
        boolean solo = source.equals("solo");
        if (!save && !solo) return result;
        var targets = save && !board.isEmpty() ? repeaterTargets(board) : java.util.List.<Pattern>of();
        long now = SystemClock.elapsedRealtime();
        if (!forkReleases.containsKey(source) || now - forkFetchedAt.get(source) >= 5 * 60_000) {
            JSONArray all = new JSONArray();
            String endpoint = "https://api.github.com/repos/" + repo + "/releases";
            for (int page = 1; ; page++) {
                byte[] data = library.request(endpoint + "?per_page=100&page=" + page,
                        Set.of("api.github.com"), 5 * 1024 * 1024);
                JSONArray releases = new JSONArray(new String(data, StandardCharsets.UTF_8));
                for (int i = 0; i < releases.length(); i++) all.put(releases.getJSONObject(i));
                if (releases.length() < 100) break;
            }
            forkReleases.put(source, all);
            forkFetchedAt.put(source, SystemClock.elapsedRealtime());
        }
        JSONArray feed = forkReleases.get(source);
        for (int i = 0; i < feed.length(); i++) {
            JSONObject release = feed.getJSONObject(i);
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) continue;
            String tag = release.getString("tag_name");
            if (save) {
                if (!tag.matches("repeater-v[0-9]+\\.[0-9]+\\.[0-9]+-savemesh\\.[0-9]+")) continue;
                appendSaveAssets(result, release, tag, board, targets);
            } else if (tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) {
                appendSoloAssets(result, release, tag, board, role);
            }
        }
        return result;
    }

    private java.util.List<Pattern> repeaterTargets(String board) throws JSONException {
        var patterns = new java.util.ArrayList<Pattern>();
        for (int i = 0; i < boards.length(); i++) {
            var entry = boards.getJSONObject(i);
            if (!board.equals(entry.getString("name"))) continue;
            var roles = entry.getJSONArray("roles");
            for (int j = 0; j < roles.length(); j++) {
                var role = roles.getJSONObject(j);
                var release = role.optJSONObject("release");
                if (!role.getString("id").equals("repeater") || release == null) continue;
                var files = release.getJSONObject("files");
                for (var keys = files.keys(); keys.hasNext();) {
                    String rule = files.getString(keys.next());
                    int wildcard = rule.indexOf(".*?");
                    if (wildcard > 0) patterns.add(Pattern.compile(rule.substring(0, wildcard), Pattern.CASE_INSENSITIVE));
                }
            }
        }
        return patterns;
    }

    private static void appendSaveAssets(JSONArray result, JSONObject release, String tag, String board,
                                         java.util.List<Pattern> targets) throws JSONException {
        String version = tag.substring("repeater-".length());
        var filename = Pattern.compile("(.+)-" + Pattern.quote(version) + "-[0-9a-f]{7,12}(-merged)?\\.(bin|uf2|zip|hex)");
        var assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            var asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            if (!safeName(name) || !asset.optString("digest").matches("sha256:[0-9a-f]{64}")) continue;
            var parts = filename.matcher(name);
            boolean firmware = parts.matches();
            if (!firmware && !name.equals("SHA256SUMS.txt")) continue;
            String kind = !firmware ? "checksums" : parts.group(2) == null ? parts.group(3) : "merged";
            boolean target = firmware && !board.isEmpty() && targets.stream().anyMatch(p -> p.matcher(parts.group(1)).matches());
            boolean m5 = target && board.equals(M5) && kind.equals("bin") &&
                    parts.group(1).equalsIgnoreCase("ThinkNode_M5_repeater");
            boolean validatedApp = target && (kind.equals("uf2") && uf2Start(board) != 0 ||
                    kind.equals("bin") && (board.equals(T_DECK) || board.equals(XIAO)));
            boolean exportOnly = !target || !m5 && !validatedApp;
            result.put(option(tag, version, name, m5, exportOnly)
                    .put("types", new JSONArray().put(m5 ? "flash-update" : kind)));
        }
    }

    private static void appendSoloAssets(JSONArray result, JSONObject release, String tag, String board,
                                         String role) throws JSONException {
        var assets = release.getJSONArray("assets");
        String targetName = "solo-" + tag + "-WioTrackerL1.uf2";
        for (int i = 0; i < assets.length(); i++) {
            var asset = assets.getJSONObject(i);
            String name = asset.optString("name");
            if (!safeName(name) || !firmwareName(name) ||
                    !asset.optString("digest").matches("sha256:[0-9a-f]{64}")) continue;
            boolean target = board.equals(WIO) && role.equals("solo") && name.equals(targetName);
            result.put(option(tag, tag, name, false, !target));
        }
    }

    private static JSONObject option(String tag, String version, String name, boolean m5) throws JSONException {
        return option(tag, version, name, m5, false);
    }

    private static JSONObject option(String tag, String version, String name, boolean m5, boolean exportOnly)
            throws JSONException {
        String lower = name.toLowerCase(Locale.ROOT);
        String type = m5 ? "flash-update" : lower.endsWith(".uf2") ? "uf2" : lower.endsWith(".zip") ? "zip" :
                lower.endsWith(".hex") ? "hex" : "bin";
        return new JSONObject().put("tag", tag).put("version", version).put("name", name)
                .put("types", new JSONArray().put(type)).put("appTag", m5 ? tag : "").put("exportOnly", exportOnly);
    }

    private static boolean firmwareName(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".+\\.(uf2|bin|zip|hex)");
    }

    static boolean safeName(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,219}");
    }
}
