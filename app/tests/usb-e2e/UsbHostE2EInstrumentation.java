package dev.meshforge.fieldflasher.usbtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class UsbHostE2EInstrumentation extends Instrumentation {
    private static final String APP = "dev.meshforge.fieldflasher";
    private static final int MAX_UF2_BYTES = 4 * 1024 * 1024;
    private Bundle arguments = Bundle.EMPTY;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? Bundle.EMPTY : arguments;
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            String mode = arguments.getString("mode", "");
            String board = arguments.getString("board", "");
            String result = switch (mode) {
                case "serial" -> testSerial(board);
                case "esp32-probe" -> testEsp32Probe(board);
                case "dfu" -> testDfu(board, arguments.getString("imageFile", ""));
                default -> throw new IOException("Set mode to serial, esp32-probe or dfu.");
            };
            results.putString("usb_e2e", result);
            sendStatus(0, results);
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            results.putString("usb_e2e", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            sendStatus(0, results);
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private String testSerial(String expectedBoard) throws Exception {
        Activity activity = launchApp();
        UsbManager manager = activity.getSystemService(UsbManager.class);
        UsbDevice device = soleDevice(manager, false);
        Object inspection = inspectViaApp(activity, manager, device);
        Object identity = invoke(method(inspection.getClass(), "identity"), inspection);
        if (identity == null) {
            throw new IOException("USB serial opened, but the radio returned no supported MeshCore identity. Use normal MeshCore mode, not the bootloader.");
        }
        String model = (String) invoke(method(identity.getClass(), "model"), identity);
        String version = (String) invoke(method(identity.getClass(), "version"), identity);
        String role = (String) invoke(method(identity.getClass(), "role"), identity);
        if (model == null || model.isBlank() || version == null || version.isBlank()) {
            throw new IOException("The USB identity response did not contain a model and firmware version.");
        }
        String board = (String) invoke(method(activity.getClass(), "boardOf", UsbDevice.class, identity.getClass()),
                activity, device, identity);
        if (!expectedBoard.isBlank() && !expectedBoard.equals(board)) {
            throw new IOException("Expected board " + expectedBoard + " but USB reports " + board + ".");
        }
        return "USB_SERIAL_PASS id=" + device.getVendorId() + ":" + device.getProductId() +
                " board=" + printable(board.isBlank() ? model : board) + " version=" + printable(version) +
                " role=" + printable(role);
    }

    private String testDfu(String expectedBoard, String imageFile) throws Exception {
        if (expectedBoard.isBlank()) throw new IOException("Provide the board name to verify against INFO_UF2.TXT.");
        if (imageFile.isBlank() || imageFile.contains("/") || imageFile.contains("\\") || imageFile.equals("..")) {
            throw new IOException("Provide a UF2 filename installed in the app's external files directory.");
        }
        Activity activity = launchApp();
        UsbManager manager = activity.getSystemService(UsbManager.class);
        UsbDevice device = soleDevice(manager, true);
        inspectViaApp(activity, manager, device);

        File directory = activity.getExternalFilesDir(null);
        if (directory == null) throw new IOException("Android external app storage is unavailable.");
        byte[] image = readBounded(new File(directory, imageFile));
        ClassLoader loader = activity.getClassLoader();
        Class<?> catalogue = loader.loadClass(APP + ".MeshCoreCatalogue");
        int uf2Start = (int) invoke(method(catalogue, "uf2Start", String.class), null, expectedBoard);
        if (uf2Start == 0) throw new IOException("The app has no verified UF2 application address for " + expectedBoard + ".");
        Class<?> firmwareLibrary = loader.loadClass(APP + ".FirmwareLibrary");
        invoke(method(firmwareLibrary, "validateUf2", byte[].class, int.class), null, image, uf2Start);

        Class<?> storageClass = loader.loadClass(APP + ".UsbMassStorage");
        Object storage = invoke(method(storageClass, "open", UsbManager.class, UsbDevice.class), null, manager, device);
        try {
            Class<?> volumeClass = loader.loadClass(APP + ".Uf2Volume");
            Class<?> blockDevice = loader.loadClass(APP + ".Uf2Volume$BlockDevice");
            Object plan = invoke(method(volumeClass, "plan", blockDevice, int.class), null, storage, image.length);
            String infoText = (String) invoke(method(plan.getClass(), "info"), plan);
            Class<?> infoClass = loader.loadClass(APP + ".BootloaderInfo");
            Object info = invoke(method(infoClass, "parse", String.class), null, infoText);
            boolean boardMatches = (boolean) invoke(method(infoClass, "matchesBoard", String.class), info, expectedBoard);
            if (!boardMatches) {
                Object boardId = invoke(method(infoClass, "boardId"), info);
                throw new IOException("UF2 drive identifies " + boardId + ", not " + expectedBoard + ". No firmware was written.");
            }
            long[] sectors = (long[]) invoke(method(plan.getClass(), "sectors"), plan);
            long blocks = (long) invoke(method(storageClass, "blockCount"), storage);
            if (sectors.length != image.length / 512 || sectors.length == 0) {
                throw new IOException("The bootloader did not reserve the expected number of UF2 sectors.");
            }
            for (long sector : sectors) {
                if (sector < 0 || sector >= blocks) throw new IOException("UF2 write plan includes a sector outside the USB device.");
            }
            String boardId = (String) invoke(method(infoClass, "boardId"), info);
            String version = (String) invoke(method(infoClass, "version"), info);
            return "USB_DFU_READ_ONLY_PASS id=" + device.getVendorId() + ":" + device.getProductId() +
                    " board=" + printable(expectedBoard) + " boardId=" + printable(boardId) +
                    " bootloader=" + printable(version) + " blocks=" + blocks + " uf2Bytes=" + image.length +
                    " plannedSectors=" + sectors.length + " writes=0";
        } finally {
            invoke(method(storageClass, "close"), storage);
        }
    }

    private String testEsp32Probe(String expectedBoard) throws Exception {
        if (!expectedBoard.equals("Elecrow ThinkNode M5")) {
            throw new IOException("The current USB ROM/layout probe is verified only for Elecrow ThinkNode M5.");
        }
        Activity activity = launchApp();
        UsbManager manager = activity.getSystemService(UsbManager.class);
        UsbDevice device = soleDevice(manager, false);
        Object inspection = inspectViaApp(activity, manager, device);
        Object identity = invoke(method(inspection.getClass(), "identity"), inspection);
        if (identity == null) throw new IOException("The ESP32 target did not return MeshCore identity before the ROM probe.");
        String board = (String) invoke(method(activity.getClass(), "boardOf", UsbDevice.class, identity.getClass()),
                activity, device, identity);
        if (!expectedBoard.equals(board)) throw new IOException("Expected " + expectedBoard + " but USB reports " + board + ".");

        String marker = "usb-e2e-esp32-probe-" + android.os.SystemClock.elapsedRealtime();
        String start = "Phone.diagnostic('usb.e2e'," + JSONObject.quote(marker) + ");Phone.probeLayout(" + device.getDeviceId() + ")";
        evaluateJavaScript(activity, start, 10_000);
        long deadline = android.os.SystemClock.elapsedRealtime() + 120_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            String log = evaluateJavaScript(activity, "Phone.logTail()", 5_000);
            int markerAt = log.lastIndexOf(marker);
            if (markerAt >= 0) {
                String result = log.substring(markerAt);
                if (result.contains("job Completed success=true") && result.contains("Probe passed.")) {
                    if (result.contains("write Begin app-only write")) {
                        throw new IOException("ESP32 layout probe unexpectedly entered a firmware write path.");
                    }
                    return "USB_ESP32_PROBE_PASS id=" + device.getVendorId() + ":" + device.getProductId() +
                            " board=" + printable(board) + " writes=0";
                }
                if (result.contains("job Completed success=false")) {
                    throw new IOException("ESP32 USB ROM/layout probe failed: " + result);
                }
            }
            Thread.sleep(500);
        }
        throw new IOException("ESP32 probe did not finish. Accept the on-phone Check layout confirmation and retry.");
    }

    private String evaluateJavaScript(Activity activity, String script, long timeoutMs) throws Exception {
        Field viewField = field(activity.getClass(), "view");
        WebView view = (WebView) viewField.get(activity);
        if (view == null) throw new IOException("MeshForge's local UI is not ready.");
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        runOnMainSync(() -> view.evaluateJavascript(script, (ValueCallback<String>) value -> {
            result.set(value);
            completed.countDown();
        }));
        if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("MeshForge UI did not respond to the USB test.");
        String encoded = result.get();
        if (encoded == null) return "";
        Object decoded = new JSONTokener(encoded).nextValue();
        return decoded instanceof String text ? text : String.valueOf(decoded);
    }

    private Activity launchApp() throws Exception {
        Context context = getTargetContext();
        if (!context.getPackageManager().hasSystemFeature("android.hardware.usb.host")) {
            throw new IOException("The Android device does not advertise USB host support.");
        }
        Intent intent = new Intent().setClassName(APP, APP + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity activity = startActivitySync(intent);
        if (activity == null) throw new IOException("Could not launch MeshForge on the target phone.");
        waitForIdleSync();
        return activity;
    }

    private Object inspectViaApp(Activity activity, UsbManager manager, UsbDevice device) throws Exception {
        Field inspection = field(activity.getClass(), "inspection");
        inspection.set(activity, null);
        Method connect = method(activity.getClass(), "connectUsb", int.class);
        runOnMainSync(() -> invoke(connect, activity, device.getDeviceId()));
        awaitPermission(manager, device);
        long deadline = android.os.SystemClock.elapsedRealtime() + 20_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            Object current = inspection.get(activity);
            if (current != null && device.equals(invoke(method(current.getClass(), "device"), current))) return current;
            Thread.sleep(100);
        }
        throw new IOException("MeshForge did not complete its USB inspection.");
    }

    private UsbDevice soleDevice(UsbManager manager, boolean massStorage) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
        List<UsbDevice> matches = new ArrayList<>();
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            matches.clear();
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (isMassStorage(device) == massStorage) matches.add(device);
            }
            if (!matches.isEmpty()) break;
            Thread.sleep(250);
        }
        if (matches.size() != 1) {
            throw new IOException("Expected exactly one connected USB " + (massStorage ? "DFU mass-storage" : "serial") +
                    " target; found " + matches.size() + ". Check OTG and disconnect unrelated devices.");
        }
        return matches.get(0);
    }

    private void awaitPermission(UsbManager manager, UsbDevice device) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 60_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!manager.getDeviceList().containsKey(device.getDeviceName())) {
                throw new IOException("USB target disconnected while waiting for permission.");
            }
            if (manager.hasPermission(device)) return;
            Thread.sleep(250);
        }
        throw new IOException("USB permission was not granted within 60 seconds. Approve MeshForge's USB access prompt and retry.");
    }

    private static boolean isMassStorage(UsbDevice device) {
        if (device.getDeviceClass() == 8) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == 8) return true;
        }
        return false;
    }

    private byte[] readBounded(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_UF2_BYTES || file.length() % 512 != 0) {
            throw new IOException("The supplied UF2 file is missing, empty, over 4 MiB, or not sector-aligned.");
        }
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("The supplied UF2 file changed while being read.");
                offset += count;
            }
            if (input.read() != -1) throw new IOException("The supplied UF2 file changed while being read.");
        }
        return bytes;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not invoke MeshForge USB path " + method.getName(), error);
        }
    }

    private static String printable(String value) {
        return value.replaceAll("[^A-Za-z0-9._+&()/:-]", "_");
    }
}