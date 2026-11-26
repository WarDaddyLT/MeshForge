package dev.meshforge.fieldflasher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

public final class EspApplicationPlanTest {
    public static void main(String[] args) throws Exception {
        byte[] app = new byte[512];
        app[0] = (byte) 0xe9;
        var legacy = EspApplicationPlan.derive(app, merged(app, 0x20000, false));
        if (legacy.chipId() != 0 || legacy.appStart() != 0x20000 || legacy.otaLength() != 0) {
            throw new AssertionError("Expected a classic ESP32 factory layout");
        }
        app[12] = 9;
        var s3 = EspApplicationPlan.derive(app, merged(app, 0x30000, true));
        if (s3.chipId() != 9 || s3.appStart() != 0x30000 || s3.otaStart() != 0xe000 ||
                s3.minimumFlash() != 0x170000) throw new AssertionError("Expected an S3 OTA0 layout");
        byte[] corrupted = merged(app, 0x30000, true);
        corrupted[0x8010] = 3;
        rejected(app, corrupted, "checksum");
        byte[] differentApp = merged(app, 0x30000, true);
        differentApp[0x30010] = 4;
        rejected(app, differentApp, "default boot slot");
        System.out.println("EspApplicationPlan tests passed");
    }

    private static byte[] merged(byte[] app, int start, boolean ota) throws Exception {
        byte[] result = new byte[start + app.length];
        Arrays.fill(result, (byte) 0xff);
        var table = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        int at = 0x8000;
        if (ota) {
            entry(table, at, 1, 0, 0xe000, 0x2000);
            at += 32;
        }
        entry(table, at, 0, ota ? 0x10 : 0, start, 0x140000);
        at += 32;
        byte[] md5 = MessageDigest.getInstance("MD5").digest(Arrays.copyOfRange(result, 0x8000, at));
        table.putShort(at, (short) 0xebeb);
        System.arraycopy(md5, 0, result, at + 16, md5.length);
        System.arraycopy(app, 0, result, start, app.length);
        return result;
    }

    private static void entry(ByteBuffer table, int at, int type, int subtype, int start, int length) {
        table.putShort(at, (short) 0x50aa);
        table.put(at + 2, (byte) type).put(at + 3, (byte) subtype);
        table.putInt(at + 4, start).putInt(at + 8, length).putInt(at + 28, 0);
    }

    private static void rejected(byte[] app, byte[] merged, String reason) throws Exception {
        try {
            EspApplicationPlan.derive(app, merged);
            throw new AssertionError("Expected rejection for " + reason);
        } catch (IOException error) {
            if (!error.getMessage().contains(reason)) throw error;
        }
    }
}
