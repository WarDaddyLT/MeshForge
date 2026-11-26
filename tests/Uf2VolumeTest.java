package dev.meshforge.fieldflasher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Uf2VolumeTest {
    private static final String INFO = "UF2 Bootloader 0.9.2\r\nBoard-ID: nRF52840-TEcho-v1\r\n";

    public static void main(String[] args) throws Exception {
        recognizesStockTEchoLiteBootloader();
        readsBootloaderInfoWithoutPlanningAWrite();
        validatesReportedSoftDeviceAgainstTheApplicationLayout();
        plansWritesInFreeFat12Clusters();
        findsPartitionedFat16Volume();
        supportsFat32RootCluster();
        rejectsVolumesWithoutBootloaderInfo();
        rejectsImagesLargerThanAvailableFreeClusters();
        System.out.println("Uf2Volume tests passed");
    }

    private static void recognizesStockTEchoLiteBootloader() throws Exception {
        var lite = BootloaderInfo.parse("UF2 Bootloader 0.9.2-dirty\r\n" +
                "Model: T-Echo-Lite\r\nBoard-ID: T-Echo-Lite-nRF52840\r\n");
        equal(true, lite.matchesBoard("LilyGo T-Echo Lite"), "stock Lite bootloader");
        equal(false, lite.matchesBoard("LilyGo T-Echo"), "Lite ID rejected for regular T-Echo");
        var original = BootloaderInfo.parse(INFO);
        equal(true, original.matchesBoard("LilyGo T-Echo"), "original T-Echo bootloader");
        equal(false, original.matchesBoard("LilyGo T-Echo Lite"), "original ID rejected for Lite");
    }

    private static void readsBootloaderInfoWithoutPlanningAWrite() throws Exception {
        equal(INFO, Uf2Volume.readInfo(fat12(0, 1000), null), "read-only bootloader information");
        var info = BootloaderInfo.parse(INFO + "SoftDevice: S140 7.3.0\r\n");
        equal("S140 7.3.0", info.softDevice(), "reported SoftDevice");
    }

    private static void validatesReportedSoftDeviceAgainstTheApplicationLayout() throws Exception {
        var wio = BootloaderInfo.parse("UF2 Bootloader 0.9.2\nBoard-ID: TRACKER L1\nSoftDevice: S140 7.3.0\n");
        wio.requireApplicationCompatibility("Seeed Studio Wio Tracker L1 EINK", 0x27000);
        try {
            wio.requireApplicationCompatibility("Seeed Studio Wio Tracker L1 EINK", 0x26000);
            throw new AssertionError("Expected a SoftDevice mismatch");
        } catch (IOException error) {
            if (!error.getMessage().contains("requires S140 6.x")) throw error;
        }
        BootloaderInfo.parse(INFO).requireApplicationCompatibility("LilyGo T-Echo", 0x26000);
    }

    private static void plansWritesInFreeFat12Clusters() throws Exception {
        FakeDisk disk = fat12(0, 1000);
        Uf2Volume.Plan plan = Uf2Volume.plan(disk, 1024);
        equal(INFO, plan.info(), "FAT12 info file");
        equal(7L, plan.sectors()[0], "first free FAT12 sector");
        equal(8L, plan.sectors()[1], "second free FAT12 sector");
    }

    private static void findsPartitionedFat16Volume() throws Exception {
        FakeDisk disk = new FakeDisk(6100);
        byte[] mbr = new byte[512];
        mbr[446 + 4] = 0x06;
        put32(mbr, 446 + 8, 10);
        put32(mbr, 446 + 12, 6000);
        signature(mbr);
        disk.put(0, mbr);
        createFat16(disk, 10, 6000);
        Uf2Volume.Plan plan = Uf2Volume.plan(disk, 512);
        equal(INFO, plan.info(), "partitioned FAT16 info file");
        equal(37L, plan.sectors()[0], "first free FAT16 sector");
    }

    private static void supportsFat32RootCluster() throws Exception {
        FakeDisk disk = new FakeDisk(70_000);
        createFat32(disk, 0, 70_000);
        Uf2Volume.Plan plan = Uf2Volume.plan(disk, 1024);
        equal(INFO, plan.info(), "FAT32 info file");
        equal(553L, plan.sectors()[0], "first free FAT32 sector");
        equal(554L, plan.sectors()[1], "second free FAT32 sector");
    }

    private static void rejectsVolumesWithoutBootloaderInfo() throws Exception {
        FakeDisk disk = fat12(0, 1000);
        disk.put(5, new byte[512]);
        fails(() -> Uf2Volume.plan(disk, 512), "missing INFO_UF2.TXT");
    }

    private static void rejectsImagesLargerThanAvailableFreeClusters() throws Exception {
        FakeDisk disk = fat12(0, 1000);
        byte[] fat = disk.readBlocks(1, 4);
        for (int cluster = 3; cluster < 996; cluster++) setFat12(fat, cluster, 0x0fff);
        disk.put(1, fat);
        fails(() -> Uf2Volume.plan(disk, 1024 * 1024), "insufficient free clusters");
    }

    private static FakeDisk fat12(long start, int sectors) {
        FakeDisk disk = new FakeDisk(start + sectors);
        byte[] boot = boot(sectors, 1, 4, 16, 0);
        disk.put(start, boot);
        byte[] fat = new byte[4 * 512];
        fat[0] = (byte) 0xf8;
        fat[1] = (byte) 0xff;
        fat[2] = (byte) 0xff;
        setFat12(fat, 2, 0x0fff);
        disk.put(start + 1, fat);
        putInfo(disk, start + 5, start + 6, 2);
        return disk;
    }

    private static void createFat16(FakeDisk disk, long start, int sectors) {
        disk.put(start, boot(sectors, 1, 24, 16, 0));
        byte[] fat = new byte[24 * 512];
        put16(fat, 0, 0xfff8);
        put16(fat, 2, 0xffff);
        put16(fat, 4, 0xffff);
        disk.put(start + 1, fat);
        putInfo(disk, start + 25, start + 26, 2);
    }

    private static void createFat32(FakeDisk disk, long start, int sectors) {
        byte[] boot = boot(sectors, 1, 550, 0, 2);
        put32(boot, 36, 550);
        disk.put(start, boot);
        byte[] fat = new byte[550 * 512];
        put32(fat, 0, 0x0ffffff8);
        put32(fat, 4, 0xffffffffL);
        put32(fat, 8, 0x0fffffff);
        put32(fat, 12, 0x0fffffff);
        disk.put(start + 1, fat);
        putInfo(disk, start + 551, start + 552, 3);
    }

    private static byte[] boot(int sectors, int reserved, int fatSectors, int rootEntries, int rootCluster) {
        byte[] boot = new byte[512];
        put16(boot, 11, 512);
        boot[13] = 1;
        put16(boot, 14, reserved);
        boot[16] = 1;
        put16(boot, 17, rootEntries);
        if (sectors < 65_536) put16(boot, 19, sectors);
        else put32(boot, 32, sectors);
        if (rootEntries == 0) {
            put16(boot, 22, 0);
            put32(boot, 36, fatSectors);
            put32(boot, 44, rootCluster);
        } else put16(boot, 22, fatSectors);
        signature(boot);
        return boot;
    }

    private static void putInfo(FakeDisk disk, long rootLba, long dataLba, int cluster) {
        byte[] root = new byte[512];
        byte[] name = "INFO_UF2TXT".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, root, 0, name.length);
        root[11] = 0x20;
        put16(root, 26, cluster);
        byte[] info = INFO.getBytes(StandardCharsets.US_ASCII);
        put32(root, 28, info.length);
        disk.put(rootLba, root);
        byte[] data = new byte[512];
        System.arraycopy(info, 0, data, 0, info.length);
        disk.put(dataLba, data);
    }

    private static void setFat12(byte[] fat, int cluster, int value) {
        int offset = cluster + cluster / 2;
        int pair = (fat[offset] & 0xff) | (fat[offset + 1] & 0xff) << 8;
        pair = (cluster & 1) == 0 ? pair & 0xf000 | value & 0x0fff : pair & 0x000f | (value & 0x0fff) << 4;
        fat[offset] = (byte) pair;
        fat[offset + 1] = (byte) (pair >>> 8);
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void signature(byte[] data) {
        data[510] = 0x55;
        data[511] = (byte) 0xaa;
    }

    private static void fails(CheckedRunnable action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError(label + " should be rejected");
        } catch (IOException expected) {
            if (expected.getMessage() == null || expected.getMessage().isBlank()) {
                throw new AssertionError(label + " should explain the failure");
            }
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }

    private static final class FakeDisk implements Uf2Volume.BlockDevice {
        private final long blockCount;
        private final Map<Long, byte[]> sectors = new HashMap<>();

        private FakeDisk(long blockCount) { this.blockCount = blockCount; }
        @Override public long blockCount() { return blockCount; }

        @Override public byte[] readBlocks(long lba, int count) {
            byte[] result = new byte[count * 512];
            for (int i = 0; i < count; i++) {
                byte[] sector = sectors.get(lba + i);
                if (sector != null) System.arraycopy(sector, 0, result, i * 512, 512);
            }
            return result;
        }

        private byte[] get(long lba) {
            byte[] sector = sectors.get(lba);
            if (sector == null) throw new AssertionError("Missing test sector " + lba);
            return sector.clone();
        }

        private void put(long lba, byte[] data) {
            if (data.length % 512 != 0) throw new IllegalArgumentException("test sectors must be 512-byte aligned");
            for (int i = 0; i < data.length / 512; i++) {
                byte[] sector = new byte[512];
                System.arraycopy(data, i * 512, sector, 0, 512);
                sectors.put(lba + i, sector);
            }
        }
    }
}