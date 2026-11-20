package dev.meshforge.fieldflasher;

import android.os.SystemClock;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class MeshCoreIdentity {
    record Result(String model, String version) {}

    static Result query(UsbSerialPort port, DiagnosticLog log) throws IOException {
        // ArduinoSerialInterface framing; CMD_DEVICE_QUERY = 22, response = 13.
        port.write(new byte[]{'<', 2, 0, 22, 10}, 350);
        log.add("meshcore.query", "Sent DEVICE_QUERY framed 2-byte request");
        var bytes = new byte[1024];
        var readBuffer = new byte[256];
        int used = 0;
        long deadline = SystemClock.elapsedRealtime() + 850;
        while (used < bytes.length && SystemClock.elapsedRealtime() < deadline) {
            int count = port.read(readBuffer, 80);
            if (count <= 0) continue;
            int copy = Math.min(count, bytes.length - used);
            System.arraycopy(readBuffer, 0, bytes, used, copy);
            used += copy;
            Result result = parse(bytes, used);
            if (result != null) {
                log.add("meshcore.query", "DEVICE_INFO model=" + result.model() + " version=" + result.version());
                return result;
            }
        }
        log.add("meshcore.query", "No supported DEVICE_INFO response receivedBytes=" + used);
        return null;
    }

    static Result parse(byte[] data, int used) {
        for (int offset = 0; offset + 3 < used; offset++) {
            if (data[offset] != '>') continue;
            int length = (data[offset + 1] & 255) | ((data[offset + 2] & 255) << 8);
            int frame = offset + 3;
            if (length < 80 || length > 512 || frame + length > used || data[frame] != 13) continue;
            String model = text(data, frame + 20, 40);
            String version = text(data, frame + 60, 20);
            if (!model.isEmpty()) return new Result(model, version);
        }
        return null;
    }

    private static String text(byte[] bytes, int start, int count) {
        int end = start;
        while (end < start + count && bytes[end] != 0) end++;
        String value = new String(Arrays.copyOfRange(bytes, start, end), StandardCharsets.UTF_8).trim();
        return value.matches("[\\p{L}\\p{N} ._+&()/:-]+") ? value : "";
    }
}
