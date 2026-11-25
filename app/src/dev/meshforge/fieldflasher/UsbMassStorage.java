package dev.meshforge.fieldflasher;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class UsbMassStorage implements Uf2Volume.BlockDevice, AutoCloseable {
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_COMMAND_SECTORS = 16;
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint input;
    private final UsbEndpoint output;
    private final DiagnosticLog log;
    private int nextTag = 1;
    private long blockCount;

    private UsbMassStorage(UsbDeviceConnection connection, UsbInterface usbInterface,
                           UsbEndpoint input, UsbEndpoint output, DiagnosticLog log) {
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.input = input;
        this.output = output;
        this.log = log;
    }

    static UsbMassStorage open(UsbManager manager, UsbDevice device) throws IOException {
        return open(manager, device, null);
    }

    static UsbMassStorage open(UsbManager manager, UsbDevice device, DiagnosticLog log) throws IOException {
        trace(log, "Open BOT mass-storage deviceId=" + device.getDeviceId() + " interfaces=" + device.getInterfaceCount());
        UsbInterface usbInterface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            trace(log, "Inspect interface index=" + i + " id=" + candidate.getId() + " class=" +
                    candidate.getInterfaceClass() + "/" + candidate.getInterfaceSubclass() + "/" +
                    candidate.getInterfaceProtocol() + " endpoints=" + candidate.getEndpointCount());
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    candidate.getInterfaceSubclass() == 0x06 && candidate.getInterfaceProtocol() == 0x50) {
                usbInterface = candidate;
                break;
            }
        }
        if (usbInterface == null) {
            trace(log, "No USB mass-storage BOT interface found");
            throw new IOException("The radio bootloader does not expose USB mass-storage BOT.");
        }
        UsbEndpoint input = null;
        UsbEndpoint output = null;
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            trace(log, "Inspect endpoint index=" + i + " address=" + endpoint.getAddress() + " type=" +
                    endpoint.getType() + " direction=" + endpoint.getDirection() + " packetSize=" + endpoint.getMaxPacketSize());
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) input = endpoint;
            else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) output = endpoint;
        }
        if (input == null || output == null) {
            trace(log, "BOT endpoint validation failed hasInput=" + (input != null) + " hasOutput=" + (output != null));
            throw new IOException("The radio bootloader has incomplete USB storage endpoints.");
        }
        trace(log, "Selected BOT interface=" + usbInterface.getId() + " IN=" + input.getAddress() +
                " OUT=" + output.getAddress());
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            trace(log, "Android UsbManager.openDevice returned null");
            throw new IOException("Android could not open the radio's USB storage interface.");
        }
        if (!connection.claimInterface(usbInterface, true)) {
            trace(log, "UsbDeviceConnection.claimInterface failed interface=" + usbInterface.getId());
            connection.close();
            throw new IOException("Android could not claim the radio's USB storage interface.");
        }
        UsbMassStorage storage = new UsbMassStorage(connection, usbInterface, input, output, log);
        try {
            storage.readCapacity();
            trace(log, "BOT device ready blockCount=" + storage.blockCount);
            return storage;
        } catch (IOException error) {
            trace(log, "BOT open failed type=" + error.getClass().getSimpleName() + " message=" + error.getMessage());
            storage.close();
            throw error;
        }
    }

    @Override public long blockCount() { return blockCount; }

    @Override public byte[] readBlocks(long lba, int count) throws IOException {
        trace("READ blocks lba=" + lba + " count=" + count + " deviceBlocks=" + blockCount);
        if (lba < 0 || count < 1 || lba + count > blockCount) throw new IOException("USB storage read is out of range.");
        byte[] result = new byte[Math.multiplyExact(count, 512)];
        int completed = 0;
        while (completed < count) {
            int batch = Math.min(MAX_COMMAND_SECTORS, count - completed);
            byte[] cdb = new byte[10];
            cdb[0] = 0x28;
            putBe32(cdb, 2, lba + completed);
            putBe16(cdb, 7, batch);
            byte[] part = command(cdb, new byte[batch * 512], true, false);
            System.arraycopy(part, 0, result, completed * 512, part.length);
            completed += batch;
        }
        trace("READ complete lba=" + lba + " count=" + count + " bytes=" + result.length);
        return result;
    }

    void writeBlocks(long lba, byte[] data, int offset, int length, boolean finalWrite) throws IOException {
        trace("WRITE blocks lba=" + lba + " offset=" + offset + " length=" + length +
                " final=" + finalWrite + " deviceBlocks=" + blockCount);
        if (lba < 0 || offset < 0 || length <= 0 || length % 512 != 0 ||
                offset + length > data.length || lba + length / 512 > blockCount) {
            throw new IOException("USB storage write is out of range.");
        }
        int completed = 0;
        int sectors = length / 512;
        while (completed < sectors) {
            int batch = Math.min(MAX_COMMAND_SECTORS, sectors - completed);
            int batchOffset = offset + completed * 512;
            int batchBytes = batch * 512;
            byte[] cdb = new byte[10];
            cdb[0] = 0x2a;
            putBe32(cdb, 2, lba + completed);
            putBe16(cdb, 7, batch);
            command(cdb, slice(data, batchOffset, batchBytes), false,
                    finalWrite && completed + batch == sectors);
            completed += batch;
        }
        trace("WRITE complete lba=" + lba + " sectors=" + sectors + " bytes=" + length);
    }

    private void readCapacity() throws IOException {
        trace("Requesting READ CAPACITY");
        byte[] response = command(new byte[]{0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0}, new byte[8], true, false);
        long lastBlock = be32(response, 0);
        long blockSize = be32(response, 4);
        if (blockSize != 512 || lastBlock >= 0xffffffffL) {
            throw new IOException("The radio bootloader reports an unsupported USB storage geometry.");
        }
        blockCount = lastBlock + 1;
        trace("READ CAPACITY lastLba=" + lastBlock + " blockSize=" + blockSize + " blockCount=" + blockCount);
    }

    private byte[] command(byte[] cdb, byte[] data, boolean inputData, boolean finalWrite) throws IOException {
        int tag = nextTag++;
        if (nextTag == 0) nextTag = 1;
        long started = System.nanoTime();
        long lba = cdb.length >= 10 ? be32(cdb, 2) : -1;
        int blocks = cdb.length >= 10 ? ((cdb[7] & 0xff) << 8) | (cdb[8] & 0xff) : 0;
        trace("BOT command start tag=" + tag + " opcode=" + commandName(cdb[0] & 0xff) +
                " direction=" + (inputData ? "IN" : "OUT") + " lba=" + lba + " blocks=" + blocks +
                " dataBytes=" + data.length + " finalWrite=" + finalWrite);
        byte[] cbw = new byte[31];
        ByteBuffer header = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x43425355).putInt(tag).putInt(data.length);
        cbw[12] = (byte) (inputData ? 0x80 : 0);
        cbw[13] = 0;
        cbw[14] = (byte) cdb.length;
        System.arraycopy(cdb, 0, cbw, 15, cdb.length);
        try {
            transfer(output, cbw, 0, cbw.length);
            trace("BOT command wrapper sent tag=" + tag + " bytes=" + cbw.length);
            boolean dataComplete = false;
            if (data.length > 0) {
                int offset = 0;
                while (offset < data.length) {
                    int count = Math.min(4096, data.length - offset);
                    transfer(inputData ? input : output, data, offset, count);
                    offset += count;
                }
                dataComplete = true;
                trace("BOT data phase complete tag=" + tag + " bytes=" + data.length);
            }
            byte[] csw = new byte[13];
            try {
                transfer(input, csw, 0, csw.length);
            } catch (IOException error) {
                if (finalWrite && dataComplete) {
                    trace("BOT final WRITE status unavailable after complete data phase; accepting legacy bootloader behavior tag=" + tag);
                    return data;
                }
                throw error;
            }
            ByteBuffer status = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN);
            if (status.getInt() != 0x53425355 || status.getInt() != tag || status.getInt() != 0) {
                throw new IOException("The radio returned an invalid USB storage status.");
            }
            int code = status.get() & 0xff;
            if (code != 0) throw new IOException("The radio rejected a USB storage command (status " + code + ").");
            trace("BOT command complete tag=" + tag + " status=" + code + " elapsedMs=" +
                    ((System.nanoTime() - started) / 1_000_000));
            return data;
        } catch (IOException error) {
            trace("BOT command failed tag=" + tag + " elapsedMs=" + ((System.nanoTime() - started) / 1_000_000) +
                    " type=" + error.getClass().getSimpleName() + " message=" + error.getMessage());
            throw error;
        }
    }

    private void transfer(UsbEndpoint endpoint, byte[] data, int offset, int length) throws IOException {
        int completed = 0;
        while (completed < length) {
            int count = connection.bulkTransfer(endpoint, data, offset + completed, length - completed, TIMEOUT_MS);
            if (count <= 0) {
                trace("Bulk transfer failed endpoint=" + endpoint.getAddress() + " direction=" + endpoint.getDirection() +
                        " requested=" + length + " completed=" + completed + " result=" + count);
                throw new IOException("USB mass-storage transfer stopped before completion.");
            }
            completed += count;
        }
        trace("Bulk transfer complete endpoint=" + endpoint.getAddress() + " direction=" + endpoint.getDirection() +
                " bytes=" + length);
    }

    private void trace(String event) { trace(log, event); }

    private static void trace(DiagnosticLog log, String event) {
        if (log != null) log.add("usb.bot", event);
    }

    private static String commandName(int opcode) {
        return switch (opcode) {
            case 0x25 -> "READ_CAPACITY";
            case 0x28 -> "READ_10";
            case 0x2a -> "WRITE_10";
            default -> String.format(java.util.Locale.US, "SCSI_0x%02x", opcode);
        };
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        return copy;
    }

    private static void putBe16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static void putBe32(byte[] data, int offset, long value) throws IOException {
        if (value < 0 || value > 0xffffffffL) throw new IOException("USB storage address is out of range.");
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static long be32(byte[] data, int offset) {
        return ((long) data[offset] & 0xff) << 24 | ((long) data[offset + 1] & 0xff) << 16 |
                ((long) data[offset + 2] & 0xff) << 8 | (long) data[offset + 3] & 0xff;
    }

    @Override public void close() {
        try { connection.releaseInterface(usbInterface); } catch (Exception ignored) {}
        connection.close();
    }
}