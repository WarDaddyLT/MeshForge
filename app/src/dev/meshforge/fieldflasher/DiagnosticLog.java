package dev.meshforge.fieldflasher;

import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

final class DiagnosticLog {
    private static final long FILE_LIMIT = 2 * 1024 * 1024;
    private static final int RETAINED = 4;
    private final File directory;
    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private long sequence;
    private String usbSession;
    private String usbDeviceKey;
    private long usbSessionStarted;

    DiagnosticLog(File directory) { this.directory = directory; }
    private File file(int index) { return new File(directory, "flash-" + index + ".log"); }

    synchronized void beginUsbSession(String trigger, String deviceKey, String device) {
        if (usbSession != null && deviceKey.equals(usbDeviceKey)) {
            add("trace", "USB event during active session trigger=" + trigger + " device=" + device);
            return;
        }
        if (usbSession != null) endUsbSession(false, "Superseded by another USB device: " + device);
        usbSession = UUID.randomUUID().toString().substring(0, 8);
        usbDeviceKey = deviceKey;
        usbSessionStarted = System.nanoTime();
        add("trace", "BEGIN trigger=" + trigger + " device=" + device +
                " storage=app-private filesDir=" + directory.getAbsolutePath());
    }

    synchronized boolean hasUsbSession() { return usbSession != null; }

    synchronized void endUsbSession(boolean success, String summary) {
        if (usbSession == null) return;
        long elapsedMs = (System.nanoTime() - usbSessionStarted) / 1_000_000;
        add("trace", "END success=" + success + " elapsedMs=" + elapsedMs + " summary=" + summary);
        usbSession = null;
        usbDeviceKey = null;
        usbSessionStarted = 0;
    }

    synchronized void add(String category, String event) {
        String safe = String.valueOf(event).replaceAll("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}", "[hardware-id]")
                .replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > 1200) safe = safe.substring(0, 1200) + " [truncated]";
        String trace = usbSession == null ? "" : " trace=" + usbSession +
                " elapsedMs=" + ((System.nanoTime() - usbSessionStarted) / 1_000_000);
        String line = Instant.now() + " run=" + runId + " seq=" + (++sequence) + trace + " " + category + " " + safe + "\n";
        try {
            if (file(0).length() >= FILE_LIMIT) {
                for (int i = RETAINED - 1; i > 0; i--) {
                    if (file(i - 1).exists()) Files.move(file(i - 1).toPath(), file(i).toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                line = Instant.now() + " log rotation: retaining " + RETAINED + " segments\n" + line;
            }
            try (var out = new FileOutputStream(file(0), true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            Log.e("MeshForge", "Diagnostic file write failed", error);
        }
    }

    synchronized String tail() throws IOException {
        var current = file(0);
        if (!current.exists()) return "No events recorded.";
        try (var input = new RandomAccessFile(current, "r")) {
            int length = (int) Math.min(12_000, input.length());
            input.seek(input.length() - length);
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    synchronized void exportTo(OutputStream output) throws IOException {
        for (int i = RETAINED - 1; i >= 0; i--) {
            if (file(i).exists()) Files.copy(file(i).toPath(), output);
        }
    }
}
