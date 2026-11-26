package dev.meshforge.fieldflasher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

final class EspApplicationPlan {
    static final int TABLE_OFFSET = 0x8000;
    static final int TABLE_LENGTH = 0xc00;
    private static final int MAX_FLASH_SIZE = 32 * 1024 * 1024;

    record Layout(int chipId, int appStart, int appLength, int minimumFlash,
                  int otaStart, int otaLength, byte[] table) {}
    private record Partition(int type, int subtype, int start, int length, int flags) {
        int end() { return start + length; }
    }

    private EspApplicationPlan() {}

    static Layout derive(byte[] app, byte[] merged) throws Exception {
        if (app.length < 36 || (app[0] & 255) != 0xe9 || merged.length < TABLE_OFFSET + TABLE_LENGTH) {
            throw new IOException("The release does not contain a recognizable ESP application and partition table.");
        }
        int chip = (app[12] & 255) | (app[13] & 255) << 8;
        if (chip != 0 && chip != 9) throw new IOException("This ESP image targets an unsupported chip.");
        byte[] table = Arrays.copyOfRange(merged, TABLE_OFFSET, TABLE_OFFSET + TABLE_LENGTH);
        var data = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        var partitions = new ArrayList<Partition>();
        boolean checksum = false;
        int offset = 0;
        for (; offset < TABLE_LENGTH; offset += 32) {
            int magic = Short.toUnsignedInt(data.getShort(offset));
            if (magic == 0xebeb) {
                byte[] actual = MessageDigest.getInstance("MD5").digest(Arrays.copyOf(table, offset));
                if (!Arrays.equals(actual, Arrays.copyOfRange(table, offset + 16, offset + 32))) {
                    throw new IOException("The published partition table checksum does not match.");
                }
                checksum = true;
                break;
            }
            if (magic != 0x50aa) throw new IOException("The published partition table has an unsupported format.");
            int start = data.getInt(offset + 4);
            int size = data.getInt(offset + 8);
            if (start < TABLE_OFFSET + 0x1000 || size <= 0 || (start & 0xfff) != 0 ||
                    (size & 0xfff) != 0 || (long) start + size > MAX_FLASH_SIZE) {
                throw new IOException("The published partition table has invalid bounds.");
            }
            partitions.add(new Partition(table[offset + 2] & 255, table[offset + 3] & 255,
                    start, size, data.getInt(offset + 28)));
        }
        if (!checksum || partitions.isEmpty()) throw new IOException("The partition table has no verified checksum.");
        partitions.sort(Comparator.comparingInt(Partition::start));
        Partition factory = null;
        Partition ota0 = null;
        Partition otaData = null;
        int end = TABLE_OFFSET + 0x1000;
        for (Partition item : partitions) {
            if (item.start() < end) throw new IOException("The published flash partitions overlap.");
            end = item.end();
            if (item.type() == 0 && item.subtype() == 0) {
                if (factory != null) throw new IOException("Multiple factory app partitions.");
                factory = item;
            } else if (item.type() == 0 && item.subtype() == 0x10) {
                if (ota0 != null) throw new IOException("Multiple OTA app0 partitions.");
                ota0 = item;
            } else if (item.type() == 1 && item.subtype() == 0) {
                if (otaData != null || item.length() != 0x2000) throw new IOException("Unsupported OTA metadata layout.");
                otaData = item;
            }
        }
        Partition target = factory != null ? factory : otaData != null ? ota0 : null;
        if (target == null || target.flags() != 0 || (target.start() & 0xffff) != 0 ||
                target.length() < app.length || target.start() + app.length > merged.length ||
                !Arrays.equals(app, 0, app.length, merged, target.start(), target.start() + app.length)) {
            throw new IOException("The paired full image does not contain this app in the default boot slot.");
        }
        return new Layout(chip, target.start(), target.length(), end,
                otaData == null ? 0 : otaData.start(), otaData == null ? 0 : otaData.length(), table);
    }
}
