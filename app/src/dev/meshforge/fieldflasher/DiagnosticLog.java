package dev.meshforge.fieldflasher;

import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

final class DiagnosticLog {
    private static final long FILE_LIMIT = 2 * 1024 * 1024;
    private static final int RETAINED = 4;
    private final File directory;

    DiagnosticLog(File directory) { this.directory = directory; }
    private File file(int index) { return new File(directory, "flash-" + index + ".log"); }

    synchronized void add(String category, String event) {
        String safe = event.replaceAll("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}", "[hardware-id]")
                .replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > 1200) safe = safe.substring(0, 1200) + " [truncated]";
        String line = Instant.now() + " " + category + " " + safe + "\n";
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
