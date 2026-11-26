package dev.meshforge.fieldflasher;

public final class FirmwareRuntimeTest {
    public static void main(String[] args) {
        equal("1.18.0-savemesh.1", FirmwareRuntime.version("repeater-v1.18.0-savemesh.1"));
        equal("1.18.0", FirmwareRuntime.version("radio-v1.18.0-f27a72f.uf2"));
        equal("confirmed", FirmwareRuntime.compare("board", "roomServer", "1.18.0",
                "board", "room-server", "v1.18.0").status());
        equal("mismatch", FirmwareRuntime.compare("board", "repeater", "1.18.0",
                "board", "repeater", "v1.17.0").status());
        equal("unconfirmed", FirmwareRuntime.compare("board", "companionBle", "1.18.0",
                "board", "companionUsb", "v1.18.0").status());
        equal("unconfirmed", FirmwareRuntime.compare("board", "repeater", "1.18.0",
                "", "repeater", "v1.18.0").status());
        System.out.println("FirmwareRuntime tests passed");
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
