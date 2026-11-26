package dev.meshforge.fieldflasher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DiagnosticLogTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("out"), "diagnostic-log-test-");
        try {
            DiagnosticLog log = new DiagnosticLog(directory.toFile());
            log.beginUsbSession("USB_DEVICE_ATTACHED", "239a:00da:UF2", "deviceId=7 vid=239a pid=00da");
            require(log.hasUsbSession(), "USB trace session should start on device attach");
            log.add("dfu.install", "Verified image and bootloader identity");
            log.beginUsbSession("USB re-enumerated", "239a:00da:UF2", "deviceId=8 vid=239a pid=00da");
            log.add("usb.bot", "WRITE_10 acknowledged");
            log.endUsbSession(true, "UF2 transfer complete");
            require(!log.hasUsbSession(), "USB trace session should close after terminal result");
            log.add("app", "later event");

            DiagnosticLog reopened = new DiagnosticLog(directory.toFile());
            String tail = reopened.tail();
            require(tail.contains("BEGIN trigger=USB_DEVICE_ATTACHED"), "session start should persist");
            require(tail.contains("dfu.install Verified image"), "algorithm trace should persist");
            require(tail.contains("USB event during active session"), "same-device re-enumeration should stay in session");
            require(tail.contains("END success=true"), "terminal result should persist");
            require(tail.indexOf("BEGIN trigger=") < tail.indexOf("END success=true"), "session markers should be ordered");
            require(tail.contains("run=") && tail.contains("seq="), "log entries should identify process run and sequence");

            ByteArrayOutputStream exported = new ByteArrayOutputStream();
            reopened.exportTo(exported);
            require(exported.toString(StandardCharsets.UTF_8).contains("UF2 transfer complete"),
                    "export should include the persisted session");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}