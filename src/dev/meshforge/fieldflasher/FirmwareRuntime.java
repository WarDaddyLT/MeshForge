package dev.meshforge.fieldflasher;

import java.util.Locale;
import java.util.regex.Pattern;

final class FirmwareRuntime {
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?<![a-z0-9])v?([0-9]+\\.[0-9]+\\.[0-9]+(?:-(?:alpha|beta|rc|savemesh)[a-z0-9.]*)?)");

    record Result(String status, String message) {}

    private FirmwareRuntime() {}

    static String version(String text) {
        var match = VERSION.matcher(text);
        return match.find() ? match.group(1).toLowerCase(Locale.ROOT) : "";
    }

    static Result compare(String expectedBoard, String expectedRole, String expectedVersion,
                          String reportedBoard, String reportedRole, String reportedVersion) {
        String running = version(reportedVersion);
        if (!reportedBoard.isEmpty() && !expectedBoard.equals(reportedBoard)) {
            return new Result("mismatch", "USB reports " + reportedBoard + ". The selected board was " + expectedBoard + ".");
        }
        if (!expectedRole.isEmpty() && !reportedRole.isEmpty() && !sameRole(expectedRole, reportedRole)) {
            return new Result("mismatch", "The radio reports role " + reportedRole + ". The selected role was " + expectedRole + ".");
        }
        if (!expectedVersion.isEmpty() && !running.isEmpty() && !expectedVersion.equals(running)) {
            return new Result("mismatch", "The radio reports firmware " + running + ". Expected " + expectedVersion + ".");
        }
        if (reportedBoard.isEmpty() || expectedRole.isEmpty() || reportedRole.isEmpty() ||
                expectedVersion.isEmpty() || running.isEmpty() || expectedRole.equals("companionBle") || expectedRole.equals("solo")) {
            return new Result("unconfirmed", "The radio answered over USB. Check its firmware version and role on the radio.");
        }
        return new Result("confirmed", "Radio rebooted and reported " + reportedBoard + " running " + running +
                " (" + reportedRole + ").");
    }

    private static boolean sameRole(String expected, String observed) {
        if (expected.equals("roomServer")) return observed.equals("room-server");
        if (expected.equals("companionBle") || expected.equals("solo")) return observed.startsWith("companion");
        return expected.equals(observed);
    }
}
