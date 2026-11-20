package dev.meshforge.fieldflasher;

import java.io.IOException;
import java.util.regex.Pattern;

record BootloaderInfo(String version, String boardId, String model) {
    private static final Pattern VERSION = Pattern.compile(
            "(?m)^UF2 Bootloader ([A-Za-z0-9][A-Za-z0-9._+-]{0,63})(?=\\s|$)");
    private static final Pattern OTAFIX = Pattern.compile(
            "(?i)(?:^|[-_.])otafix(?:[0-9]+(?:\\.[0-9]+)*)?(?=$|[-_+])");

    static BootloaderInfo parse(String text) throws IOException {
        var version = VERSION.matcher(text);
        String id = field(text, "Board-ID");
        if (!version.find() || id.isEmpty()) throw new IOException("Expected the radio's INFO_UF2.TXT file.");
        return new BootloaderInfo(version.group(1), id, field(text, "Model"));
    }

    private static String field(String text, String label) {
        var field = Pattern.compile("(?m)^" + label + ":[ \\t]*([ -~]{1,100})\\r?$").matcher(text);
        return field.find() ? field.group(1).trim() : "";
    }

    String status() {
        if (OTAFIX.matcher(version).find()) return "installed";
        if (version.matches("0\\.[0-9]+\\.[0-9]+")) return "absent";
        return "unknown";
    }

    boolean matchesBoard(String board) {
        return switch (boardId) {
            case "WisBlock-RAK3401-Board" -> board.equals(MeshCoreCatalogue.RAK_1W);
            case "TRACKER L1" -> board.equals("Seeed Studio Wio Tracker L1 Pro") ||
                    board.equals("Seeed Studio Wio Tracker L1 EINK");
            case "nRF52840-TEcho-v1" -> board.equals("LilyGo T-Echo") ||
                    board.equals("LilyGo T-Echo Lite") || board.equals("LilyGo T-Echo Card");
            case "nRF52840-SeeedXiao-v1", "nRF52840-SeeedXiaoSense-v1" ->
                    board.equals("Seeed Studio Xiao nRF52 WIO");
            default -> false;
        };
    }

    boolean sharedBoardId() {
        return boardId.equals("TRACKER L1") || boardId.equals("nRF52840-TEcho-v1") ||
                boardId.startsWith("nRF52840-SeeedXiao");
    }

    boolean matchesFix(String filename) {
        return switch (boardId) {
            case "WisBlock-RAK3401-Board" -> filename.startsWith("wiscore_rak3401_board_bootloader-");
            case "TRACKER L1" -> filename.startsWith("wio_tracker_l1_bootloader-");
            case "nRF52840-TEcho-v1" -> filename.startsWith("lilygo_techo_bootloader-");
            case "nRF52840-SeeedXiao-v1" -> filename.startsWith("xiao_nrf52840_ble_bootloader-");
            case "nRF52840-SeeedXiaoSense-v1" -> filename.startsWith("xiao_nrf52840_ble_sense_bootloader-");
            default -> false;
        };
    }
}
