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
            case "WisBlock-RAK4631-Board" -> board.equals("RAK WisBlock / WisMesh (RAK 4631)") ||
                    board.equals("GAT-IoT GAT562 Tracker") || board.equals("GAT-IoT GAT562 30s") ||
                    board.equals("Keepteen LT1");
            case "TRACKER L1" -> board.equals("Seeed Studio Wio Tracker L1 Pro") ||
                    board.equals("Seeed Studio Wio Tracker L1 EINK");
            case "T-Echo-Lite-nRF52840" -> board.equals("LilyGo T-Echo Lite");
            case "nRF52840-TEcho-v1" -> board.equals("LilyGo T-Echo") ||
                    board.equals("LilyGo T-Echo Card");
            case "nRF52840-SeeedXiao-v1", "nRF52840-SeeedXiaoSense-v1" ->
                    board.equals("Seeed Studio Xiao nRF52 WIO");
            case "WisMesh-Tag" -> board.equals("RAK WisMesh Tag");
            case "Heltec-T096-v1" -> board.equals("Heltec Mesh Node T096");
            case "Heltec-T1" -> board.equals("Heltec Mesh Node T1");
            case "HT-n5262" -> board.equals("Heltec T114");
            case "nRF52840-SeeedSenseCAPSolarP1-v1" -> board.equals("Seeed Studio SenseCAP Solar Node P1");
            case "nRF52840-T1000-E-v1" -> board.equals("Seeed Studio SenseCAP T1000-E");
            case "nRF52840-ThinkNodeM1-v1" -> board.equals("Elecrow ThinkNode M1");
            case "nRF52840-ThinkNode-M3-v1" -> board.equals("Elecrow ThinkNode M3");
            case "nRF52840-ThinkNodeM6-v1" -> board.equals("Elecrow ThinkNode M6");
            case "nRF52840-promicro" -> board.equals("ProMicro nrf52 (faketec)");
            default -> false;
        };
    }

    boolean sharedBoardId() {
        return boardId.equals("TRACKER L1") || boardId.equals("nRF52840-TEcho-v1") ||
                boardId.equals("WisBlock-RAK4631-Board") || boardId.startsWith("nRF52840-SeeedXiao");
    }

    boolean matchesFix(String filename) {
        String family = fixFamily();
        return !family.isEmpty() && filename.startsWith(family + "-");
    }

    String fixFamily() {
        return switch (boardId) {
            case "WisBlock-RAK3401-Board" -> "wiscore_rak3401_board_bootloader";
            case "WisBlock-RAK4631-Board" -> "wiscore_rak4631_board_bootloader";
            case "TRACKER L1" -> "wio_tracker_l1_bootloader";
            case "nRF52840-TEcho-v1" -> "lilygo_techo_bootloader";
            case "nRF52840-SeeedXiao-v1" -> "xiao_nrf52840_ble_bootloader";
            case "nRF52840-SeeedXiaoSense-v1" -> "xiao_nrf52840_ble_sense_bootloader";
            case "WisMesh-Tag" -> "wismesh_tag_bootloader";
            case "Heltec-T096-v1" -> "heltec_t096_bootloader";
            case "Heltec-T1" -> "heltec_t1_bootloader";
            case "HT-n5262" -> "heltec_t114_bootloader";
            case "nRF52840-SeeedSenseCAPSolarP1-v1" -> "sensecap_solar_p1_bootloader";
            case "nRF52840-T1000-E-v1" -> "t1000_e_bootloader";
            case "nRF52840-ThinkNodeM1-v1" -> "thinknode_m1_bootloader";
            case "nRF52840-ThinkNode-M3-v1" -> "thinknode_m3_bootloader";
            case "nRF52840-ThinkNodeM6-v1" -> "thinknode_m6_bootloader";
            case "nRF52840-promicro" -> "promicro_nrf52840_bootloader";
            default -> "";
        };
    }
}
