package dev.meshforge.fieldflasher;

import android.os.SystemClock;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class RepeaterCli {
    enum Field {
        NAME("name", "Node name"),
        LAT("lat", "Latitude"),
        LON("lon", "Longitude"),
        OWNER("owner.info", "Owner info"),
        FORWARDING("repeat", "Packet forwarding"),
        ROOM_READ_ONLY("allow.read.only", "Read-only room access"),
        GUEST("guest.password", "Guest password"),
        ADMIN("password", "Admin password"),
        LOCAL_ADVERT("advert.interval", "Local advertisement interval"),
        FLOOD_ADVERT("flood.advert.interval", "Flood advertisement interval"),
        FLOOD_ALL("flood.max", "Maximum flood hops"),
        FLOOD_UNSCOPED("flood.max.unscoped", "Maximum unscoped flood hops"),
        FLOOD_ANNOUNCEMENTS("flood.max.advert", "Maximum announcement hops"),
        RADIO("radio", "LoRa radio settings"),
        TX_POWER("tx", "Transmit power"),
        DUTY("dutycycle", "Duty cycle"),
        LOOP("loop.detect", "Loop detection"),
        HASH("path.hash.mode", "Path hash size"),
        INTERFERENCE("int.thresh", "Interference threshold"),
        AGC("agc.reset.interval", "Gain control reset interval"),
        RX_DELAY("rxdelay", "Receive processing delay"),
        TX_DELAY("txdelay", "Flood retransmit delay"),
        DIRECT_DELAY("direct.txdelay", "Direct retransmit delay"),
        ADC("adc.multiplier", "Battery voltage calibration"),
        RX_GAIN("radio.rxgain", "Boosted receive gain"),
        MULTI_ACKS("multi.acks", "Multiple acknowledgments");

        final String key;
        final String label;

        Field(String key, String label) {
            this.key = key;
            this.label = label;
        }

        String validate(String value) throws IOException {
            if (this == OWNER) value = value.replace("\r\n", "\n").replace('\n', '|');
            if (value.codePoints().anyMatch(Character::isISOControl) ||
                    !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
                throw new IOException(label + " contains invalid characters.");
            }
            return switch (this) {
                case NAME -> {
                    if (value.isBlank() || bytes(value).length > 31 ||
                            value.chars().anyMatch(c -> "[]\\:,?*".indexOf(c) >= 0)) {
                        throw new IOException("Use a node name of 1–31 UTF-8 bytes without brackets, backslashes, colons, commas, question marks or asterisks.");
                    }
                    yield value;
                }
                case OWNER -> {
                    if (bytes(value).length > 119) throw new IOException("Owner info is limited to 119 UTF-8 bytes.");
                    yield value;
                }
                case FORWARDING, ROOM_READ_ONLY, RX_GAIN -> {
                    if (!value.equals("on") && !value.equals("off")) throw new IOException("Choose On or Off.");
                    yield value;
                }
                case LAT -> decimal(value, -90, 90, label, 5);
                case LON -> decimal(value, -180, 180, label, 5);
                case GUEST, ADMIN -> {
                    if (bytes(value).length > 15 || this == ADMIN && value.isBlank()) {
                        throw new IOException(label + " must fit in 15 UTF-8 bytes. An admin password cannot be blank.");
                    }
                    yield value;
                }
                case LOCAL_ADVERT -> {
                    int minutes = integer(value, 0, 240, label);
                    if (minutes != 0 && (minutes < 60 || minutes % 2 != 0)) {
                        throw new IOException("Use 0 or an even interval from 60 to 240 minutes.");
                    }
                    yield Integer.toString(minutes);
                }
                case FLOOD_ADVERT -> {
                    int hours = integer(value, 0, 168, label);
                    if (hours == 1 || hours == 2) throw new IOException("Use 0 or an interval from 3 to 168 hours.");
                    yield Integer.toString(hours);
                }
                case FLOOD_ALL, FLOOD_UNSCOPED, FLOOD_ANNOUNCEMENTS -> number(value, 0, 64);
                case TX_POWER -> number(value, 1, 22);
                case DUTY -> decimal(value, 1, 100, label, 1);
                case LOOP -> {
                    if (!Set.of("off", "minimal", "moderate", "strict").contains(value)) {
                        throw new IOException("Choose an available loop detection mode.");
                    }
                    yield value;
                }
                case HASH -> number(value, 0, 2);
                case INTERFERENCE -> number(value, 0, 255);
                case AGC -> {
                    int seconds = integer(value, 0, 1020, label);
                    if (seconds % 4 != 0) throw new IOException("Use a gain control reset interval in multiples of 4 seconds.");
                    yield Integer.toString(seconds);
                }
                case RX_DELAY -> decimal(value, 0, 20, label, 1);
                case TX_DELAY, DIRECT_DELAY -> decimal(value, 0, 2, label, 1);
                case ADC -> decimal(value, 0, 1000000, label, 3);
                case MULTI_ACKS -> number(value, 0, 1);
                case RADIO -> normalizeRadio(value);
            };
        }

        private String number(String value, int min, int max) throws IOException {
            return Integer.toString(integer(value, min, max, label));
        }

        boolean supported(String role, int firmware) {
            return switch (this) {
                case ROOM_READ_ONLY -> role.equals("room-server");
                case OWNER -> firmware >= 112;
                case LOOP, HASH -> firmware >= 114;
                case FLOOD_UNSCOPED, FLOOD_ANNOUNCEMENTS -> firmware >= 116;
                default -> true;
            };
        }
    }

    record Snapshot(String key, String version, String role, String clock, Map<Field, String> settings,
                    Map<String, Integer> acl, RepeaterTables.Regions regions) {
        boolean sameConfiguration(Snapshot other) {
            return key.equalsIgnoreCase(other.key) && version.equals(other.version) && role.equals(other.role) &&
                    settings.equals(other.settings) && java.util.Objects.equals(acl, other.acl) &&
                    java.util.Objects.equals(regions, other.regions);
        }
    }

    private final UsbSerialPort serial;
    private final DiagnosticLog log;
    private final byte[] received = new byte[256];
    private int position;
    private int length;

    RepeaterCli(UsbSerialPort serial, DiagnosticLog log) {
        this.serial = serial;
        this.log = log;
    }

    String readRole() throws IOException {
        String role = setting("role");
        if (role == null) {
            // A previous serial probe or boot message can interrupt the first exchange.
            log.add("repeater.cli", "Retrying read-only role query");
            role = setting("role");
        }
        return role == null ? "" : role.replace('_', '-');
    }

    Snapshot read() throws IOException {
        String role = readRole();
        if (!role.equals("repeater") && !role.equals("room-server")) {
            throw new IOException("The device did not identify itself as a MeshCore repeater or room server.");
        }
        String key = setting("public.key");
        if (key == null || !key.matches("[a-fA-F0-9]{64}")) {
            throw new IOException("The radio did not return a valid public key.");
        }
        String version = command("ver", "ver");
        if (version == null || !version.matches("[Vv]?\\d[^\\p{Cc}]{0,150}")) {
            throw new IOException("The radio did not return a firmware version.");
        }
        var semantic = Pattern.compile("^[Vv]?(\\d{1,2})\\.(\\d{1,2})\\.\\d+").matcher(version);
        int firmware = semantic.find() ? 100 * Integer.parseInt(semantic.group(1)) + Integer.parseInt(semantic.group(2)) : 0;
        String clock = command("clock", "clock");
        if (clock != null && !clock.matches("\\d{2}:\\d{2}:\\d{2} - \\d{1,2}/\\d{1,2}/\\d{4} UTC")) clock = null;
        Map<Field, String> settings = new LinkedHashMap<>();
        for (Field field : Field.values()) {
            if (!field.supported(role, firmware)) continue;
            if (field == Field.ADMIN) {
                settings.put(field, "");
                continue;
            }
            String value = readField(field);
            if (value != null && value.codePoints().noneMatch(Character::isISOControl)) {
                settings.put(field, value);
            }
        }
        if (!settings.containsKey(Field.NAME) || !settings.containsKey(Field.FORWARDING)) {
            throw new IOException("The radio did not return its basic settings.");
        }
        return new Snapshot(key, version, role, clock, settings, RepeaterTables.readAcl(this),
                RepeaterTables.readRegions(this));
    }

    void write(Field field, String value) throws IOException {
        if (field == Field.ADMIN) {
            if (!("password now: " + value).equals(command("password " + value, "set admin password"))) {
                throw new IOException("The radio did not confirm the admin password change.");
            }
            return;
        }
        if (field == Field.DUTY && legacyDuty) {
            BigDecimal duty = new BigDecimal(value);
            if (duty.compareTo(BigDecimal.TEN) < 0) throw new IOException("This firmware supports a duty cycle of 10% or higher.");
            String af = BigDecimal.valueOf(100).divide(duty, 5, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).toPlainString();
            expectOk(command("set af " + af, "set duty cycle"), field.label);
        } else {
            String reply = command("set " + field.key + " " + value, "set " + field.key);
            expectOk(reply, field.label);
            if (field == Field.ADC && value.equals("0") && "OK - using default board multiplier".equals(reply)) {
                // The getter returns the effective multiplier, including after a reset to defaults.
                if (readField(field) != null) return;
                throw new IOException("The radio did not report its default battery voltage multiplier.");
            }
        }
        String actual = readField(field);
        try {
            if (actual != null && field.validate(actual).equals(value)) return;
        } catch (IOException invalidReadback) {
            // Report the same uncertainty for malformed and mismatched readback.
        }
        throw new IOException(field.label + " could not be verified after saving.");
    }

    static void expectOk(String reply, String label) throws IOException {
        if (reply == null || !(reply.strip().equals("OK") || reply.strip().startsWith("OK -"))) {
            throw new IOException(label + " was not acknowledged by the radio.");
        }
    }

    private boolean legacyDuty;

    private String readField(Field field) throws IOException {
        String value = setting(field.key);
        if (field == Field.DUTY) {
            if (value != null && value.matches("[0-9]{1,3}(\\.[0-9])?%")) return rounded(value.replace("%", ""), 1);
            value = setting("af");
            if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,7})?")) return null;
            legacyDuty = true;
            return BigDecimal.valueOf(100).divide(new BigDecimal(value).add(BigDecimal.ONE), 1, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
        if (value == null) return null;
        if (field == Field.LAT || field == Field.LON) return rounded(value, 5);
        if (field == Field.RX_DELAY || field == Field.TX_DELAY || field == Field.DIRECT_DELAY) return rounded(value, 1);
        if (field == Field.ADC) return rounded(value, 3);
        if (field == Field.LOCAL_ADVERT && value.equals("2")) return "0";
        if (field != Field.RADIO) return value;
        String[] parts = value.split(",", -1);
        if (parts.length != 4 || !parts[0].matches("[0-9]{1,4}(\\.[0-9]{1,7})?")) return null;
        // MeshCore prints the stored float with up to seven fractional digits.
        parts[0] = new BigDecimal(parts[0]).setScale(3, RoundingMode.HALF_UP).toPlainString();
        try {
            return field.validate(String.join(",", parts));
        } catch (IOException unrecognizedRadio) {
            return null;
        }
    }

    private static String rounded(String value, int digits) {
        if (!value.matches("-?[0-9]{1,7}(\\.[0-9]{1,7})?")) return null;
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    String setting(String key) throws IOException {
        String reply = command("get " + key, "get " + key);
        if (reply != null && reply.startsWith("> ")) return reply.substring(2);
        if (">".equals(reply)) return "";
        return null;
    }

    String command(String command, String label) throws IOException {
        return exchange(command, label, false, false);
    }

    String table(String command, boolean prefixed) throws IOException {
        return exchange(command, command, true, prefixed);
    }

    private String exchange(String command, String label, boolean multiline, boolean prefixed) throws IOException {
        byte[] request = bytes(command + "\r");
        if (request.length > 158) throw new IOException("The setting is too long to send to the radio.");
        long start = SystemClock.elapsedRealtime();
        serial.write(request, 1000);
        long deadline = SystemClock.elapsedRealtime() + 2200;
        long quietUntil = deadline;
        int count = 0;
        boolean echoed = false;
        boolean collecting = false;
        var payload = new StringBuilder();
        var line = new ByteArrayOutputStream(256);
        try {
            while (SystemClock.elapsedRealtime() < deadline && count < 32768) {
                if (position == length) {
                    length = serial.read(received, 50);
                    position = 0;
                    if (length == 0) {
                        if (collecting && SystemClock.elapsedRealtime() >= quietUntil && line.size() == 0) return payload.toString();
                        continue;
                    }
                }
                int next = received[position++] & 0xff;
                count++;
                if (next != '\n') {
                    if (line.size() < 1024) line.write(next);
                    continue;
                }
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                boolean crlf = text.endsWith("\r");
                if (crlf) text = text.substring(0, text.length() - 1);
                if (echoed && !collecting && text.startsWith("  -> ")) {
                    if (!multiline || !prefixed || crlf) return text.substring(5);
                    collecting = true;
                    payload.append(text.substring(5)).append('\n');
                } else if (multiline && echoed && (collecting || !prefixed && text.equals("ACL:"))) {
                    collecting = true;
                    if (prefixed && crlf && text.isEmpty()) return payload.toString();
                    payload.append(text).append('\n');
                    if (prefixed && crlf) return payload.toString();
                }
                if (collecting && !prefixed) quietUntil = SystemClock.elapsedRealtime() + 350;
                if (text.equals(command)) echoed = true;
            }
            return null;
        } finally {
            log.add("repeater.cli", label + " txBytes=" + request.length + " rxBytes=" + count +
                    " echoed=" + echoed + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
        }
    }

    private static String normalizeRadio(String value) throws IOException {
        String[] parts = value.split(",", -1);
        if (parts.length != 4) throw new IOException("Enter frequency, bandwidth, spreading factor and coding rate.");
        String frequency = decimal(parts[0].strip(), 150, 2500, "Frequency", 3);
        int maxBandwidth = Double.parseDouble(frequency) >= 2400 ? 1625 : 500;
        String bandwidth = decimal(parts[1].strip(), 7, maxBandwidth, "Bandwidth", 3);
        int sf = integer(parts[2].strip(), 5, 12, "Spreading factor");
        int cr = integer(parts[3].strip(), 5, 8, "Coding rate");
        return frequency + "," + bandwidth + "," + sf + "," + cr;
    }

    private static int integer(String value, int min, int max, String label) throws IOException {
        if (value.matches("[0-9]{1,4}")) {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        }
        throw new IOException(label + " must be an integer from " + min + " to " + max + ".");
    }

    private static String decimal(String value, int min, int max, String label, int digits) throws IOException {
        if (value.matches("-?[0-9]{1,7}(\\.[0-9]{1," + digits + "})?")) {
            var number = new BigDecimal(value);
            if (number.compareTo(BigDecimal.valueOf(min)) >= 0 && number.compareTo(BigDecimal.valueOf(max)) <= 0) {
                return number.stripTrailingZeros().toPlainString();
            }
        }
        throw new IOException(label + " must be from " + min + " to " + max + " with up to " + digits + " decimal places.");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
