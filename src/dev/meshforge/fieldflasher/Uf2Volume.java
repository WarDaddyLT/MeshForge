package dev.meshforge.fieldflasher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class Uf2Volume {
    interface BlockDevice {
        long blockCount();
        byte[] readBlocks(long lba, int count) throws IOException;
    }

    record Plan(String info, long[] sectors) {
        int sectorCount() { return sectors.length; }
    }

    private record Geometry(long start, long totalSectors, long fatStart, long fatSectors,
                            long rootStart, long rootSectors, long dataStart, long clusterCount,
                            int sectorsPerCluster, int fatType, long rootCluster) {}

    private record DirectoryEntry(long firstCluster, long size) {}

    private Uf2Volume() {}

    static Plan plan(BlockDevice device, int imageSize) throws IOException {
        return plan(device, imageSize, null);
    }

    static Plan plan(BlockDevice device, int imageSize, DiagnosticLog log) throws IOException {
        trace(log, "Planning UF2 volume imageBytes=" + imageSize + " deviceBlocks=" + device.blockCount());
        if (imageSize <= 0 || imageSize > 4 * 1024 * 1024 || imageSize % 512 != 0) {
            throw new IOException("The verified UF2 has an unsupported size.");
        }
        trace(log, "Image sector count=" + (imageSize / 512) + " sectorSize=512");
        Geometry geometry = mount(device, log);
        trace(log, "Mounted FAT" + geometry.fatType() + " start=" + geometry.start() +
                " totalSectors=" + geometry.totalSectors() + " sectorsPerCluster=" + geometry.sectorsPerCluster() +
                " clusterCount=" + geometry.clusterCount() + " dataStart=" + geometry.dataStart());
        byte[] fat = readFat(device, geometry, log);
        String text = readInfo(device, geometry, fat, log);

        int requiredSectors = imageSize / 512;
        long[] destinations = new long[requiredSectors];
        int written = 0;
        long examinedClusters = 0;
        trace(log, "Allocating unused FAT clusters requiredSectors=" + requiredSectors);
        for (long cluster = 2; cluster < geometry.clusterCount() + 2 && written < requiredSectors; cluster++) {
            examinedClusters++;
            if (fatValue(fat, geometry.fatType(), cluster) != 0) continue;
            long firstSector = geometry.start() + geometry.dataStart() +
                    (cluster - 2) * geometry.sectorsPerCluster();
            for (int sector = 0; sector < geometry.sectorsPerCluster() && written < requiredSectors; sector++) {
                destinations[written++] = firstSector + sector;
            }
        }
        if (written != requiredSectors) {
            trace(log, "Insufficient free space allocatedSectors=" + written + " requiredSectors=" + requiredSectors +
                    " examinedClusters=" + examinedClusters);
            throw new IOException("The UF2 bootloader does not expose enough unused storage for this firmware.");
        }
        trace(log, "Sector plan complete allocatedSectors=" + written + " examinedClusters=" + examinedClusters +
                " firstLba=" + destinations[0] + " lastLba=" + destinations[destinations.length - 1]);
        return new Plan(text, destinations);
    }

    static String readInfo(BlockDevice device, DiagnosticLog log) throws IOException {
        Geometry geometry = mount(device, log);
        return readInfo(device, geometry, readFat(device, geometry, log), log);
    }

    private static String readInfo(BlockDevice device, Geometry geometry, byte[] fat, DiagnosticLog log)
            throws IOException {
        DirectoryEntry info = findInfoFile(device, geometry, fat, log);
        if (info == null) throw new IOException("The USB bootloader has no root INFO_UF2.TXT file.");
        trace(log, "Found INFO_UF2.TXT firstCluster=" + info.firstCluster() + " bytes=" + info.size());
        String text = readFile(device, geometry, fat, info, log);
        trace(log, "Read INFO_UF2.TXT bytes=" + text.length());

        return text;
    }

    private static Geometry mount(BlockDevice device, DiagnosticLog log) throws IOException {
        trace(log, "Mount step: read sector zero");
        byte[] sector = read(device, 0, 1, log);
        Geometry direct = geometry(device, sector, 0, log);
        if (direct != null) {
            trace(log, "Mount step: sector zero is a FAT boot sector");
            return direct;
        }
        if (u8(sector, 510) != 0x55 || u8(sector, 511) != 0xaa) {
            trace(log, "Mount step: sector zero has no MBR signature");
            throw new IOException("The USB device does not contain a supported FAT volume.");
        }
        trace(log, "Mount step: MBR signature found; checking four partition entries");
        Geometry found = null;
        for (int i = 0; i < 4; i++) {
            int entry = 446 + i * 16;
            int type = u8(sector, entry + 4);
            if (!isFatPartition(type)) {
                trace(log, "Partition entry=" + i + " skipped type=0x" + Integer.toHexString(type));
                continue;
            }
            long start = u32(sector, entry + 8);
            trace(log, "Partition entry=" + i + " FAT type=0x" + Integer.toHexString(type) + " start=" + start);
            if (start == 0 || start >= device.blockCount()) {
                trace(log, "Partition entry=" + i + " skipped out-of-range start=" + start);
                continue;
            }
            Geometry candidate = geometry(device, read(device, start, 1, log), start, log);
            if (candidate == null) {
                trace(log, "Partition entry=" + i + " did not contain a supported FAT boot sector");
                continue;
            }
            if (found != null) throw new IOException("The USB device contains multiple FAT volumes.");
            found = candidate;
        }
        if (found == null) throw new IOException("The USB device does not contain a supported FAT volume.");
        trace(log, "Mount step: selected partition start=" + found.start() + " FAT" + found.fatType());
        return found;
    }

    private static boolean isFatPartition(int type) {
        return type == 0x01 || type == 0x04 || type == 0x06 || type == 0x0b || type == 0x0c || type == 0x0e;
    }

    private static Geometry geometry(BlockDevice device, byte[] boot, long start, DiagnosticLog log) {
        if (boot.length != 512) {
            trace(log, "BPB rejected start=" + start + " bytes=" + boot.length + " expectedBytes=512");
            return null;
        }
        if (u8(boot, 510) != 0x55 || u8(boot, 511) != 0xaa || u16(boot, 11) != 512) {
            trace(log, "BPB rejected start=" + start + " signature=" +
                    Integer.toHexString(u8(boot, 510)) + Integer.toHexString(u8(boot, 511)) +
                    " bytesPerSector=" + u16(boot, 11));
            return null;
        }
        int sectorsPerCluster = u8(boot, 13);
        int reserved = u16(boot, 14);
        int fats = u8(boot, 16);
        long rootEntries = u16(boot, 17);
        long total = u16(boot, 19);
        if (total == 0) total = u32(boot, 32);
        long fatSectors = u16(boot, 22);
        if (fatSectors == 0) fatSectors = u32(boot, 36);
        trace(log, "BPB start=" + start + " sectorsPerCluster=" + sectorsPerCluster + " reserved=" + reserved +
                " fats=" + fats + " rootEntries=" + rootEntries + " totalSectors=" + total + " fatSectors=" + fatSectors);
        if (sectorsPerCluster == 0 || (sectorsPerCluster & (sectorsPerCluster - 1)) != 0 ||
                sectorsPerCluster > 128 || reserved == 0 || fats == 0 || fats > 2 ||
                total == 0 || fatSectors == 0 || start + total > device.blockCount()) {
            trace(log, "BPB rejected: invalid geometry or range volumeEnd=" + (start + total) +
                    " deviceBlocks=" + device.blockCount());
            return null;
        }
        long rootSectors = (rootEntries * 32 + 511) / 512;
        long dataStart = reserved + (long) fats * fatSectors + rootSectors;
        if (dataStart >= total) {
            trace(log, "BPB rejected: metadata reaches past volume dataStart=" + dataStart + " total=" + total);
            return null;
        }
        long clusterCount = (total - dataStart) / sectorsPerCluster;
        int fatType = clusterCount < 4085 ? 12 : clusterCount < 65525 ? 16 : 32;
        trace(log, "BPB derived FAT" + fatType + " clusterCount=" + clusterCount + " rootSectors=" + rootSectors +
                " dataStart=" + dataStart);
        if ((fatType == 32 && rootEntries != 0) || (fatType != 32 && rootEntries == 0)) {
            trace(log, "BPB rejected: FAT type and root-entry count disagree FAT" + fatType + " rootEntries=" + rootEntries);
            return null;
        }
        long rootCluster = fatType == 32 ? u32(boot, 44) & 0x0fffffffL : 0;
        if (fatType == 32 && (rootCluster < 2 || rootCluster >= clusterCount + 2)) {
            trace(log, "BPB rejected: invalid FAT32 root cluster=" + rootCluster);
            return null;
        }
        long fatStart = start + reserved;
        long requiredFatBytes = fatType == 12 ? ((clusterCount + 2) * 3 + 1) / 2 :
                fatType == 16 ? (clusterCount + 2) * 2 : (clusterCount + 2) * 4;
        if (fatSectors * 512 < requiredFatBytes) {
            trace(log, "BPB rejected: FAT capacity too small availableBytes=" + (fatSectors * 512) +
                    " requiredBytes=" + requiredFatBytes);
            return null;
        }
        trace(log, "BPB accepted FAT" + fatType + " rootCluster=" + rootCluster + " requiredFatBytes=" + requiredFatBytes);
        return new Geometry(start, total, fatStart, fatSectors, start + reserved + (long) fats * fatSectors,
                rootSectors, dataStart, clusterCount, sectorsPerCluster, fatType, rootCluster);
    }

    private static byte[] readFat(BlockDevice device, Geometry geometry, DiagnosticLog log) throws IOException {
        long size = geometry.fatSectors() * 512;
        if (size > 8 * 1024 * 1024) throw new IOException("The USB bootloader FAT table is too large to inspect safely.");
        trace(log, "Reading FAT table lba=" + geometry.fatStart() + " sectors=" + geometry.fatSectors() + " bytes=" + size);
        byte[] result = read(device, geometry.fatStart(), Math.toIntExact(geometry.fatSectors()), log);
        trace(log, "FAT table read complete bytes=" + result.length);
        return result;
    }

    private static DirectoryEntry findInfoFile(BlockDevice device, Geometry geometry, byte[] fat,
                                               DiagnosticLog log) throws IOException {
        if (geometry.fatType() != 32) {
            long remaining = geometry.rootSectors();
            long lba = geometry.rootStart();
            trace(log, "Searching fixed root directory lba=" + lba + " sectors=" + remaining);
            while (remaining > 0) {
                int count = (int) Math.min(16, remaining);
                trace(log, "Reading root directory batch lba=" + lba + " sectors=" + count);
                DirectoryEntry entry = findInfoEntry(read(device, lba, count, log));
                if (entry != null) return entry;
                lba += count;
                remaining -= count;
            }
            return null;
        }
        long cluster = geometry.rootCluster();
        trace(log, "Searching FAT32 root directory cluster=" + cluster);
        for (int traversed = 0; traversed < 4096; traversed++) {
            long lba = clusterLba(geometry, cluster);
            trace(log, "Reading FAT32 root cluster=" + cluster + " lba=" + lba +
                    " sectors=" + geometry.sectorsPerCluster() + " traversal=" + traversed);
            DirectoryEntry entry = findInfoEntry(read(device, lba, geometry.sectorsPerCluster(), log));
            if (entry != null) return entry;
            long next = fatValue(fat, 32, cluster);
            if (isEndOfChain(32, next)) {
                trace(log, "FAT32 root directory reached end-of-chain without INFO_UF2.TXT");
                return null;
            }
            if (next < 2 || next >= geometry.clusterCount() + 2) {
                trace(log, "FAT32 root directory has invalid next cluster=" + next);
                break;
            }
            cluster = next;
        }
        throw new IOException("The FAT root directory is malformed.");
    }

    private static DirectoryEntry findInfoEntry(byte[] directory) {
        byte[] expected = "INFO_UF2TXT".getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset + 32 <= directory.length; offset += 32) {
            int first = u8(directory, offset);
            if (first == 0) return null;
            int attributes = u8(directory, offset + 11);
            if (first == 0xe5 || attributes == 0x0f || (attributes & 0x08) != 0) continue;
            if (!Arrays.equals(expected, Arrays.copyOfRange(directory, offset, offset + 11))) continue;
            long cluster = ((long) u16(directory, offset + 20) << 16) | u16(directory, offset + 26);
            return new DirectoryEntry(cluster, u32(directory, offset + 28));
        }
        return null;
    }

    private static String readFile(BlockDevice device, Geometry geometry, byte[] fat, DirectoryEntry file,
                                   DiagnosticLog log) throws IOException {
        if (file.size() <= 0 || file.size() > 4096 || file.firstCluster() < 2 ||
                file.firstCluster() >= geometry.clusterCount() + 2) {
            throw new IOException("The bootloader INFO_UF2.TXT file has invalid FAT metadata.");
        }
        byte[] content = new byte[(int) file.size()];
        int copied = 0;
        long cluster = file.firstCluster();
        for (int traversed = 0; copied < content.length && traversed < 4096; traversed++) {
            long lba = clusterLba(geometry, cluster);
            trace(log, "Reading INFO_UF2.TXT cluster=" + cluster + " lba=" + lba +
                    " traversal=" + traversed + " copied=" + copied + "/" + content.length);
            byte[] data = read(device, lba, geometry.sectorsPerCluster(), log);
            int count = Math.min(data.length, content.length - copied);
            System.arraycopy(data, 0, content, copied, count);
            copied += count;
            if (copied == content.length) break;
            long next = fatValue(fat, geometry.fatType(), cluster);
            if (isEndOfChain(geometry.fatType(), next) || next < 2 || next >= geometry.clusterCount() + 2) {
                trace(log, "INFO_UF2.TXT chain ended or invalid before expected bytes copied=" + copied +
                        " expected=" + content.length + " nextCluster=" + next);
                throw new IOException("The bootloader INFO_UF2.TXT file is truncated.");
            }
            cluster = next;
        }
        if (copied != content.length) throw new IOException("The bootloader INFO_UF2.TXT file is too fragmented.");
        return new String(content, StandardCharsets.US_ASCII);
    }

    private static long clusterLba(Geometry geometry, long cluster) throws IOException {
        if (cluster < 2 || cluster >= geometry.clusterCount() + 2) throw new IOException("Invalid FAT cluster.");
        return geometry.start() + geometry.dataStart() + (cluster - 2) * geometry.sectorsPerCluster();
    }

    private static long fatValue(byte[] fat, int type, long cluster) throws IOException {
        long offset = type == 12 ? cluster + cluster / 2 : type == 16 ? cluster * 2 : cluster * 4;
        if (offset < 0 || offset + (type == 32 ? 4 : 2) > fat.length) throw new IOException("Invalid FAT entry.");
        int at = (int) offset;
        return switch (type) {
            case 12 -> {
                int pair = u16(fat, at);
                yield (cluster & 1) == 0 ? pair & 0x0fff : (pair >>> 4) & 0x0fff;
            }
            case 16 -> u16(fat, at);
            case 32 -> u32(fat, at) & 0x0fffffffL;
            default -> throw new IOException("Unsupported FAT type.");
        };
    }

    private static boolean isEndOfChain(int type, long value) {
        return switch (type) {
            case 12 -> value >= 0x0ff8;
            case 16 -> value >= 0xfff8;
            case 32 -> value >= 0x0ffffff8;
            default -> true;
        };
    }

    private static byte[] read(BlockDevice device, long lba, int count, DiagnosticLog log) throws IOException {
        trace(log, "Volume read request lba=" + lba + " sectors=" + count);
        if (count < 1 || lba < 0 || lba + count > device.blockCount()) throw new IOException("USB storage read is out of range.");
        byte[] result = new byte[Math.multiplyExact(count, 512)];
        int completed = 0;
        while (completed < count) {
            int batch = Math.min(16, count - completed);
            trace(log, "Volume read batch lba=" + (lba + completed) + " sectors=" + batch);
            byte[] part = device.readBlocks(lba + completed, batch);
            if (part.length != batch * 512) throw new IOException("The USB bootloader returned an incomplete sector read.");
            System.arraycopy(part, 0, result, completed * 512, part.length);
            completed += batch;
        }
        trace(log, "Volume read complete lba=" + lba + " sectors=" + count + " bytes=" + result.length);
        return result;
    }

    private static void trace(DiagnosticLog log, String event) {
        if (log != null) log.add("uf2.plan", event);
    }

    private static int u8(byte[] bytes, int offset) { return bytes[offset] & 0xff; }
    private static int u16(byte[] bytes, int offset) { return u8(bytes, offset) | u8(bytes, offset + 1) << 8; }
    private static long u32(byte[] bytes, int offset) {
        return (long) u8(bytes, offset) | (long) u8(bytes, offset + 1) << 8 |
                (long) u8(bytes, offset + 2) << 16 | (long) u8(bytes, offset + 3) << 24;
    }
}