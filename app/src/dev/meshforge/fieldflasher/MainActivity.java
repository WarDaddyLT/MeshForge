package dev.meshforge.fieldflasher;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import com.hoho.android.usbserial.driver.*;
import org.json.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PERMISSION = "dev.meshforge.fieldflasher.USB_PERMISSION";
    private static final int EXPORT = 1;
    private static final int READ_UF2_INFO = 2;
    private static final int SAVE_APP = 3;
    private static final int PICK_LOCAL_FIRMWARE = 4;
    private String localBoard;
    private volatile FirmwareLibrary.Image localImage;
    private ReleaseRepositories repositories;
    private UsbDevice readingBootloader;
    private FirmwareLibrary.Image exportingImage;
    private volatile BootloaderInspection bootloaderInspection;
    private record BootloaderInspection(UsbDevice device, BootloaderInfo info) {}
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream(32768);
    private WebView view;
    private volatile float systemTopInset;
    private DiagnosticLog log;
    private FirmwareLibrary library;
    private MeshCoreCatalogue catalogue;
    private UsbManager usb;
    private volatile UsbSerialPort port;
    private volatile UsbDevice activeDevice;
    private volatile String readError;
    private volatile FirmwareLibrary.Image staged;
    private volatile boolean approved;
    private record Inspection(UsbDevice device, MeshCoreIdentity.Result identity) {}
    private volatile Inspection inspection;
    private int pendingPermission = -1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            String action = intent.getAction();
            describe("event=" + action, device);
            if (PERMISSION.equals(action)) {
                if (device == null || device.getDeviceId() != pendingPermission) {
                    log.add("permission", "Ignored a result without a matching USB request");
                    return;
                }
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        usb.hasPermission(device);
                pendingPermission = -1;
                log.add("permission", "Completed granted=" + granted);
                if (granted) inspect(device);
                else publishDevice("USB permission was declined.");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                var inspected = inspection;
                if (inspected != null && inspected.device().equals(device)) inspection = null;
                var bootloader = bootloaderInspection;
                if (bootloader != null && bootloader.device().equals(device)) bootloaderInspection = null;
                if (device != null && device.equals(readingBootloader)) readingBootloader = null;
                if (device != null && device.getDeviceId() == pendingPermission) pendingPermission = -1;
                if (device != null && device.equals(activeDevice)) {
                    closePort("active device detached");
                    js("window.onDetach()");
                }
                publishDevice(null);
            } else {
                publishDevice(null);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        log = new DiagnosticLog(getFilesDir());
        usb = getSystemService(UsbManager.class);
        repositories = new ReleaseRepositories(this);
        log.add("app", "Started version=0.1.15 Android=" + Build.VERSION.RELEASE + " API=" +
                Build.VERSION.SDK_INT + " model=" + Build.MODEL + " usbHost=" +
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST));
        try {
            library = new FirmwareLibrary(this, log);
        } catch (Exception error) {
            failure("library", error);
        }
        try {
            catalogue = new MeshCoreCatalogue(this);
        } catch (Exception error) {
            failure("catalogue", error);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        var layout = getWindow().getAttributes();
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(layout);
        getWindow().setNavigationBarColor(0xff7b4a21);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setStatusBarContrastEnforced(false);
        view = new WebView(this);
        view.setBackgroundColor(0xffd7983a);
        var frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(0xff7b4a21);
        frame.addView(view, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setOnApplyWindowInsetsListener((element, insets) -> {
            var edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            element.setPadding(edges.left, 0, edges.right, edges.bottom);
            float top = edges.top / getResources().getDisplayMetrics().density;
            if (top != systemTopInset) {
                systemTopInset = top;
                js("window.onSystemTopInset?.(" + top + ")");
            }
            return WindowInsets.CONSUMED;
        });
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setBlockNetworkLoads(true);
        view.addJavascriptInterface(new Bridge(), "Phone");
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest request) { return true; }
            @Override public void onReceivedError(WebView w, WebResourceRequest request, WebResourceError error) {
                log.add("web", "Load error code=" + error.getErrorCode() + " " + error.getDescription());
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                log.add("web", message.messageLevel() + " line=" + message.lineNumber() + " " + message.message());
                return true;
            }
        });
        setContentView(frame);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> view.evaluateJavascript("window.goBack?.() ?? false", handled -> {
                    if (!approved && "false".equals(handled)) finish();
                }));
        registerReceiver(receiver, new IntentFilter(PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        IntentFilter changes = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        changes.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(receiver, changes, Context.RECEIVER_EXPORTED);
        view.loadUrl("file:///android_asset/index.html");
        describe("initial inventory", null);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            describe("activity attachment", intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class));
            publishDevice(null);
        }
    }

    private void js(String command) {
        if (view != null) view.post(() -> view.evaluateJavascript(command, null));
    }

    private void failure(String stage, Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            log.add(stage, cause.getClass().getSimpleName() + ": " + cause.getMessage());
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().startsWith("dev.meshforge") || frame.getClassName().startsWith("com.hoho")) {
                    log.add(stage, "at " + frame);
                }
            }
            if (cause == cause.getCause()) break;
        }
    }

    private void describe(String event, UsbDevice device) {
        log.add("usb", event + " deviceCount=" + usb.getDeviceList().size());
        if (device == null) {
            if (event.equals("initial inventory")) for (var connected : usb.getDeviceList().values()) {
                describe("enumerated", connected);
            }
            return;
        }
        log.add("usb", String.format(Locale.US, "id=%d vid=%04x pid=%04x product=%s class=%d/%d/%d permission=%s",
                device.getDeviceId(), device.getVendorId(), device.getProductId(), device.getProductName(),
                device.getDeviceClass(), device.getDeviceSubclass(), device.getDeviceProtocol(), usb.hasPermission(device)));
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            log.add("descriptor", "interface=" + it.getId() + " alt=" + it.getAlternateSetting() +
                    " class=" + it.getInterfaceClass() + "/" + it.getInterfaceSubclass() + "/" + it.getInterfaceProtocol());
            for (int j = 0; j < it.getEndpointCount(); j++) {
                UsbEndpoint ep = it.getEndpoint(j);
                log.add("descriptor", "endpoint=" + ep.getAddress() + " direction=" + ep.getDirection() +
                        " type=" + ep.getType() + " packet=" + ep.getMaxPacketSize() + " interval=" + ep.getInterval());
            }
        }
    }

    private UsbDevice candidate() {
        UsbDevice only = null;
        var inspected = inspection;
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (!isM5Bridge(device)) continue;
            if (only != null) return null;
            only = device;
        }
        if (only != null) {
            var reported = inspected != null && only.equals(inspected.device()) ? inspected.identity() : null;
            String board = boardOf(only, reported);
            if (!board.isEmpty() && !board.equals("Elecrow ThinkNode M5")) return null;
        }
        return only;
    }

    private static boolean isM5Bridge(UsbDevice device) {
        return device.getVendorId() == 0x1a86 && device.getProductId() == 0x7522;
    }

    private UsbDevice findDevice(int id) {
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (device.getDeviceId() == id) return device;
        }
        return null;
    }

    private String deviceType(UsbDevice device) {
        if (isM5Bridge(device)) return "WCH USB serial (M5-compatible bridge)";
        if (device.getVendorId() == 0x303a && device.getProductId() == 0x1001) return "Espressif native USB Serial/JTAG";
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver instanceof Ch34xSerialDriver) return "WCH USB serial";
        if (driver instanceof Cp21xxSerialDriver) return "Silicon Labs CP210x USB serial";
        if (driver instanceof CdcAcmSerialDriver) return "CDC ACM USB serial";
        if (driver != null) return "USB serial (" + driver.getClass().getSimpleName() + ")";
        if (isMassStorage(device)) return "USB mass storage";
        return "USB device";
    }

    private static boolean isMassStorage(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) return true;
        }
        return false;
    }

    private void publishDevice(String status) {
        var devices = new ArrayList<>(usb.getDeviceList().values());
        devices.sort(Comparator.comparingInt(UsbDevice::getDeviceId));
        UsbDevice m5Bridge = candidate();
        var inspected = inspection;
        JSONArray inventory = new JSONArray();
        for (UsbDevice device : devices) {
            boolean connected = inspected != null && device.equals(inspected.device());
            var identity = connected ? inspected.identity() : null;
            var bootloader = bootloaderInspection;
            var info = bootloader != null && device.equals(bootloader.device()) ? bootloader.info() : null;
            try {
                JSONArray checkedBoards = new JSONArray();
                JSONArray checkedFixes = new JSONArray();
                if (info != null && catalogue != null) {
                    JSONArray boards = catalogue.boards();
                    for (int i = 0; i < boards.length(); i++) {
                        String board = boards.getJSONObject(i).getString("name");
                        if (info.matchesBoard(board) || (!info.sharedBoardId() && board.equals(catalogue.match(info.model())))) {
                            checkedBoards.put(board);
                            JSONArray fixes = catalogue.otaFixes(board);
                            for (int j = 0; j < fixes.length(); j++) {
                                String filename = fixes.getString(j);
                                if (info.matchesFix(filename)) checkedFixes.put(filename);
                            }
                        }
                    }
                }
                inventory.put(new JSONObject()
                        .put("id", device.getDeviceId())
                        .put("name", device.getProductName() == null ? "USB device" : device.getProductName())
                        .put("type", deviceType(device))
                        .put("code", String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId()))
                        .put("allowed", usb.hasPermission(device))
                        .put("inspected", connected)
                        .put("board", boardOf(device, identity))
                        .put("reportedModel", identity != null ? identity.model() : "")
                        .put("reportedVersion", identity != null ? identity.version() : "")
                        .put("m5", device.equals(m5Bridge))
                        .put("storage", isMassStorage(device))
                        .put("bootloader", info == null ? JSONObject.NULL : new JSONObject()
                                .put("version", info.version()).put("id", info.boardId()).put("status", info.status())
                                .put("boards", checkedBoards).put("fixes", checkedFixes)));
            } catch (JSONException error) {
                failure("usb.inventory", error);
            }
        }
        js("window.onUsb(" + inventory + "," + JSONObject.quote(status == null ? "" : status) + ")");
    }

    private String boardOf(UsbDevice device, MeshCoreIdentity.Result identity) {
        if (catalogue == null || isMassStorage(device)) return "";
        String board = identity != null ? catalogue.match(identity.model()) : null;
        if (board == null) board = catalogue.match(device.getProductName());
        return board == null ? "" : board;
    }

    private void connectUsb(int id) {
        UsbDevice device = findDevice(id);
        if (device == null || approved) {
            publishDevice("The USB device is unavailable.");
            return;
        }
        if (usb.hasPermission(device)) {
            inspect(device);
            return;
        }
        if (pendingPermission >= 0) return;
        pendingPermission = id;
        var intent = new Intent(PERMISSION).setPackage(getPackageName());
        var reply = PendingIntent.getBroadcast(this, device.getDeviceId(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        log.add("permission", "Requesting USB access id=" + pendingPermission);
        try {
            usb.requestPermission(device, reply);
        } catch (Exception error) {
            pendingPermission = -1;
            failure("permission", error);
            publishDevice("Could not request USB access.");
        }
    }

    private void inspect(UsbDevice device) {
        worker.execute(() -> {
            try {
                if (approved || !device.equals(findDevice(device.getDeviceId())) || !usb.hasPermission(device)) {
                    throw new IOException("The selected USB device is no longer available.");
                }
                UsbDeviceConnection connection = usb.openDevice(device);
                if (connection == null) throw new IOException("Android could not open the selected USB device.");
                try {
                    byte[] descriptors = connection.getRawDescriptors();
                    log.add("usb.connect", "Opened id=" + device.getDeviceId() + " type=" + deviceType(device) +
                            " rawDescriptorBytes=" + (descriptors == null ? 0 : descriptors.length));
                } finally {
                    connection.close();
                }
                if (!device.equals(findDevice(device.getDeviceId()))) throw new IOException("The USB device detached.");
                var identity = isMassStorage(device) ? null : queryIdentity(device);
                if (!device.equals(findDevice(device.getDeviceId()))) throw new IOException("The USB device detached.");
                inspection = new Inspection(device, identity);
                describe("connected descriptors", device);
                publishDevice(identity != null ? "USB probe read the MeshCore device information." :
                        isMassStorage(device) ? "USB storage detected. Read INFO_UF2.TXT to check the bootloader." :
                        "USB descriptors read. The device did not report a MeshCore identity.");
            } catch (Exception error) {
                failure("usb.connect", error);
                publishDevice(error.getMessage());
            }
        });
    }

    private MeshCoreIdentity.Result queryIdentity(UsbDevice device) {
        UsbSerialDriver driver = isM5Bridge(device) ? new Ch34xSerialDriver(device) :
                UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null || driver.getPorts().isEmpty()) return null;
        UsbDeviceConnection connection = usb.openDevice(device);
        if (connection == null) return null;
        UsbSerialPort serial = driver.getPorts().get(0);
        try {
            serial.open(connection);
            serial.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            if (device.getVendorId() == 0x303a && device.getProductId() == 0x1001) serial.setDTR(true);
            return MeshCoreIdentity.query(serial, log);
        } catch (Exception error) {
            failure("meshcore.query", error);
            return null;
        } finally {
            try { serial.close(); } catch (Exception error) { connection.close(); }
        }
    }

    private synchronized String openPort(int id) throws Exception {
        if (!approved || port != null) throw new IOException("No confirmed flash session, or port already open");
        UsbDevice device = candidate();
        if (device == null || device.getDeviceId() != id || !usb.hasPermission(device)) {
            throw new IOException("The approved radio is disconnected or permission changed");
        }
        log.add("serial", "Opening Ch34xSerialDriver with explicit WCH 1a86:7522 mapping id=" + id);
        var connection = usb.openDevice(device);
        if (connection == null) throw new IOException("Android openDevice returned null");
        UsbSerialPort serial = new Ch34xSerialDriver(device).getPorts().get(0);
        try {
            serial.open(connection);
            serial.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception error) {
            try { serial.close(); } catch (Exception ignored) { connection.close(); }
            throw error;
        }
        synchronized (incoming) { incoming.reset(); }
        readError = null;
        activeDevice = device;
        port = serial;
        log.add("serial", "Opened baud=115200 dataBits=8 parity=none stopBits=1 readTimeoutMs=50 writeTimeoutMs=4000");
        new Thread(() -> readLoop(serial), "usb-read").start();
        return "";
    }

    private void readLoop(UsbSerialPort serial) {
        byte[] buffer = new byte[16384];
        try {
            while (port == serial) {
                int count = serial.read(buffer, 50);
                if (count <= 0) continue;
                log.add("serial.rx", "bytes=" + count);
                synchronized (incoming) {
                    if (incoming.size() + count > 256 * 1024) throw new IOException("Receive buffer overflow");
                    incoming.write(buffer, 0, count);
                }
            }
        } catch (Exception error) {
            if (port == serial) {
                readError = error.getMessage() == null ? "USB read failed" : error.getMessage();
                failure("serial.rx", error);
            }
        }
        log.add("serial.rx", "Reader stopped");
    }

    private synchronized void closePort(String reason) {
        UsbSerialPort serial = port;
        port = null;
        activeDevice = null;
        if (serial != null) {
            log.add("serial", "Closing port: " + reason);
            try { serial.close(); } catch (Exception error) { failure("serial.close", error); }
        }
    }

    private void showConfirmation(int id, boolean probeOnly, String source, String role) {
        UsbDevice device = candidate();
        if (device == null || device.getDeviceId() != id || !usb.hasPermission(device) || approved || library == null) {
            publishDevice("Grant permission and connect only the M5.");
            js("window.onComplete(false,'The selected USB device is unavailable.')");
            return;
        }
        js("window.onPreparing()");
        worker.execute(() -> {
            try {
                FirmwareLibrary.Image image = probeOnly ? null : library.load();
                JSONObject plan = probeOnly ? library.layoutDetails().put("tag", "Layout probe") : image.details();
                String tag = plan.getString("tag");
                String imageSource = plan.optString("source");
                boolean save = imageSource.equals("save");
                if (!probeOnly && (!source.equals(save ? "save" : "official") ||
                        !role.equals(save ? "repeater" : "companionBle"))) {
                    throw new IOException("Download an M5 image for the selected source and role first.");
                }
                String change = save ? "This installs SaveMeshcore Repeater. Existing settings and identity migration are unverified. " :
                        "This installs the MeshCore BLE companion. ";
                view.post(() -> new AlertDialog.Builder(this)
                        .setTitle(probeOnly ? "Probe M5 bootloader" : "Confirm M5 app update")
                        .setMessage(probeOnly ? "The app will reset the M5 into its bootloader, read its chip and layout, then reset it again." :
                                "Confirm that the target is your ThinkNode M5. " + change + tag + ".")
                        .setNegativeButton("Cancel", (dialog, button) -> js("window.onComplete(false,'Operation canceled.')"))
                        .setOnCancelListener(dialog -> js("window.onComplete(false,'Operation canceled.')"))
                        .setPositiveButton(probeOnly ? "Probe" : "Update M5", (dialog, button) -> {
                            if (candidate() == null || !device.equals(candidate()) || !usb.hasPermission(device)) {
                                js("window.onComplete(false,'The attached device changed.')");
                                return;
                            }
                            approved = true;
                            staged = image;
                            log.add("plan", "User confirmed probeOnly=" + probeOnly + " deviceId=" + id + " tag=" + tag +
                                    " address=0x10000 sha256=" + plan.optString("sha256"));
                            js("window.startFlash(" + id + "," + plan + "," + probeOnly + ")");
                        }).show());
            } catch (Exception error) {
                failure("plan", error);
                js("window.onComplete(false," + JSONObject.quote(error.getMessage()) + ")");
            }
        });
    }

    private void exportLog() {
        log.add("log", "Export requested");
        var intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "meshforge-debug.log");
        startActivityForResult(intent, EXPORT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == READ_UF2_INFO) {
            UsbDevice device = readingBootloader;
            readingBootloader = null;
            if (device != null && result == RESULT_OK && data != null && data.getData() != null) {
                worker.execute(() -> readBootloader(device, data.getData()));
            }
            return;
        }
        if (request == PICK_LOCAL_FIRMWARE) {
            String board = localBoard;
            localBoard = null;
            if (board != null && result == RESULT_OK && data != null && data.getData() != null) {
                worker.execute(() -> readLocalFirmware(board, data.getData()));
            }
            return;
        }
        if (request == SAVE_APP) {
            var image = exportingImage;
            exportingImage = null;
            if (image == null || result != RESULT_OK || data == null || data.getData() == null) {
                js("window.onAssetStatus('Firmware export canceled.')");
                return;
            }
            worker.execute(() -> {
                try (var output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (output == null) throw new IOException("Selected file destination is unavailable");
                    output.write(image.bytes());
                    output.flush();
                    log.add("firmware.export", "Export completed board=" + image.details().getString("board") +
                            " sha256=" + image.details().getString("sha256"));
                    js("window.onAssetStatus('Firmware file saved.')");
                } catch (Exception error) {
                    failure("firmware.export", error);
                    js("window.onAssetStatus('Firmware export failed.')");
                }
            });
            return;
        }
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        worker.execute(() -> {
            try (var output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (output == null) throw new IOException("Selected log destination is unavailable");
                log.add("log", "Exporting retained segments");
                log.exportTo(output);
                js("window.onExport(true)");
            } catch (Exception error) {
                failure("log", error);
                js("window.onExport(false)");
            }
        });
    }

    private void showSaveFirmware(FirmwareLibrary.Image image) {
        runOnUiThread(() -> {
            if (exportingImage != null) return;
            exportingImage = image;
            try {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, image.details().optString("name")), SAVE_APP);
            } catch (ActivityNotFoundException error) {
                exportingImage = null;
                failure("firmware.export", error);
                js("window.onAssetStatus('Android Files is unavailable.')");
            }
        });
    }

    private void readLocalFirmware(String board, android.net.Uri uri) {
        localImage = null;
        try {
            String name;
            try (var info = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (info == null || !info.moveToFirst()) throw new IOException("The file name is unavailable.");
                name = info.getString(0);
            }
            if (name == null || name.length() > 220 ||
                    !name.toLowerCase(Locale.ROOT).matches(".+\\.(uf2|bin|zip|hex)")) {
                throw new IOException("Choose a UF2, BIN, ZIP, or HEX firmware file.");
            }
            byte[] bytes;
            try (var input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("The selected file cannot be opened.");
                bytes = FirmwareLibrary.limited(input, FirmwareLibrary.EXPORT_LIMIT);
            }
            if (bytes.length == 0) throw new IOException("The selected file is empty.");
            String sha = FirmwareLibrary.digest("SHA-256", bytes);
            JSONObject details = new JSONObject().put("board", board).put("name", name)
                    .put("size", bytes.length).put("sha256", sha).put("route", "inspect");
            if (board.equals("Elecrow ThinkNode M5") && name.toLowerCase(Locale.ROOT).endsWith(".bin")) {
                try {
                    var approvedImage = library.load();
                    if (sha.equals(approvedImage.details().getString("sha256"))) {
                        details.put("route", "remote")
                                .put("source", approvedImage.details().optString("source").equals("save") ? "save" : "official")
                                .put("role", approvedImage.details().optString("role", "companionBle"));
                    }
                } catch (FileNotFoundException unavailable) {
                    log.add("firmware.local", "No cached approved M5 release to compare");
                }
            }
            if (name.toLowerCase(Locale.ROOT).endsWith(".uf2") && MeshCoreCatalogue.uf2Start(board) != 0) {
                try {
                    FirmwareLibrary.validateUf2(bytes, MeshCoreCatalogue.uf2Start(board));
                    details.put("route", "uf2");
                    localImage = new FirmwareLibrary.Image(bytes, details);
                } catch (IOException incompatible) {
                    log.add("firmware.local", "UF2 did not match the selected board's application range");
                }
            }
            log.add("firmware.local", "Read bytes=" + bytes.length + " sha256=" + sha + " route=" + details.optString("route"));
            js("window.onLocalFirmware(" + details + ",null)");
        } catch (Exception error) {
            failure("firmware.local", error);
            js("window.onLocalFirmware(null," + JSONObject.quote(error.getMessage()) + ")");
        }
    }

    private void publishRepositories() throws JSONException {
        js("window.onRepositories(" + repositories.links() + ")");
    }

    private void readBootloader(UsbDevice device, android.net.Uri uri) {
        try {
            var resolver = getContentResolver();
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) {
                throw new IOException("Choose INFO_UF2.TXT from the radio's USB drive in Files.");
            }
            String document = android.provider.DocumentsContract.getDocumentId(uri);
            int divider = document.indexOf(':');
            if (divider <= 0 || !document.substring(0, divider).matches("(?i)[0-9a-f]{4}-[0-9a-f]{4}") ||
                    !document.substring(divider + 1).equalsIgnoreCase("INFO_UF2.TXT") ||
                    !device.equals(findDevice(device.getDeviceId())) || !isMassStorage(device)) {
                throw new IOException("Choose INFO_UF2.TXT at the root of the connected radio's USB drive.");
            }
            long drives = usb.getDeviceList().values().stream().filter(MainActivity::isMassStorage).count();
            if (drives != 1) throw new IOException("Connect one USB drive when checking its bootloader.");
            try (var input = resolver.openInputStream(uri)) {
                if (input == null) throw new IOException("The bootloader information file could not be opened.");
                var info = BootloaderInfo.parse(new String(FirmwareLibrary.limited(input, 4096),
                        java.nio.charset.StandardCharsets.US_ASCII));
                if (!device.equals(findDevice(device.getDeviceId()))) throw new IOException("USB drive disconnected.");
                bootloaderInspection = new BootloaderInspection(device, info);
                log.add("bootloader", "INFO_UF2 id=" + info.boardId() + " version=" + info.version() +
                        " otafix=" + info.status());
                publishDevice("Bootloader information read.");
            }
        } catch (Exception error) {
            failure("bootloader", error);
            publishDevice(error.getMessage());
        }
    }

    @Override protected void onDestroy() {
        log.add("app", "Activity destroyed");
        closePort("activity closed");
        unregisterReceiver(receiver);
        worker.shutdown();
        super.onDestroy();
    }

    private final class Bridge {
        @JavascriptInterface public float systemTopInset() { return systemTopInset; }

        @JavascriptInterface public void ready() {
            log.add("app", "Local UI ready");
            if (catalogue != null) js("window.onBoards(" + catalogue.boards() + ")");
            publishDevice(null);
            try { publishRepositories(); } catch (JSONException error) { failure("repositories", error); }
            if (library != null) worker.execute(() -> {
                try {
                    var cached = library.load();
                    js("window.onDownload(" + cached.details() + ",null)");
                } catch (FileNotFoundException ordinaryFirstRun) {
                    log.add("cache", "No downloaded image");
                } catch (Exception error) { failure("cache", error); }
                try {
                    js("window.onCachedExport(" + library.loadExport().details() + ",null)");
                } catch (FileNotFoundException ordinaryFirstRun) {
                    log.add("cache", "No exportable file cached");
                } catch (Exception error) { failure("cache.export", error); }
            });
        }
        @JavascriptInterface public void scan() {
            describe("initial inventory", null);
            int count = usb.getDeviceList().size();
            publishDevice(count == 0 ? "Scan complete. Android sees no USB devices." :
                    "Scan complete. USB devices detected: " + count + ".");
        }
        @JavascriptInterface public void connectUsb(int id) { runOnUiThread(() -> MainActivity.this.connectUsb(id)); }
        @JavascriptInterface public void versions(int request, String board, String role, String source) {
            if (catalogue == null || library == null) {
                js("window.onVersions(" + request + ",null,'The firmware catalogue is unavailable.')");
                return;
            }
            worker.execute(() -> {
                try {
                    var versions = catalogue.versions(board, role, source, library);
                    log.add("catalogue", "board=" + board + " role=" + role + " source=" + source + " versions=" + versions.length());
                    js("window.onVersions(" + request + "," + versions + ",null)");
                } catch (Exception error) {
                    failure("catalogue", error);
                    js("window.onVersions(" + request + ",null," + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }
        @JavascriptInterface public void confirm(int id, boolean probeOnly, String source, String role) {
            runOnUiThread(() -> showConfirmation(id, probeOnly, source, role));
        }
        @JavascriptInterface public void download(String board, String role, String source, String tag, String filename) {
            worker.execute(() -> {
                try {
                    if (catalogue == null || library == null) throw new IOException("Firmware catalogue is unavailable.");
                    var versions = catalogue.versions(board, role, source, library);
                    JSONObject selected = null;
                    for (int i = 0; i < versions.length(); i++) {
                        JSONObject option = versions.getJSONObject(i);
                        if (tag.equals(option.getString("tag")) && filename.equals(option.getString("name"))) {
                            selected = option;
                            break;
                        }
                    }
                    if (selected == null) throw new IOException("This release is unavailable for the selected board and role.");
                    log.add("download", "Selected board=" + board + " role=" + role + " source=" + source + " tag=" + tag);
                    var details = library.fetch(source, board, role, selected);
                    js((details.getBoolean("direct") ? "window.onDownload(" : "window.onCachedExport(") + details + ",null)");
                } catch (Exception error) {
                    failure("download", error);
                    js("window.onDownload(null," + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }
        @JavascriptInterface public void saveImage(String board, String role, String source) {
            worker.execute(() -> {
                try {
                    var image = library.loadExport();
                    var details = image.details();
                    if (!board.equals(details.getString("board")) || !role.equals(details.getString("role")) ||
                            !source.equals(details.getString("source"))) throw new IOException("Download the selected firmware first.");
                    showSaveFirmware(image);
                } catch (Exception error) {
                    failure("firmware.export", error);
                    js("window.onAssetStatus(" + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }
        @JavascriptInterface public void pickLocal(String board) {
            runOnUiThread(() -> {
                if (localBoard != null || approved || library == null) return;
                localBoard = board;
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_LOCAL_FIRMWARE);
                } catch (ActivityNotFoundException error) {
                    localBoard = null;
                    failure("firmware.local", error);
                    js("window.onLocalFirmware(null,'Android Files is unavailable.')");
                }
            });
        }
        @JavascriptInterface public void saveLocal(String board, String sha) {
            var image = localImage;
            if (image != null && image.details().optString("board").equals(board) &&
                    image.details().optString("sha256").equals(sha)) showSaveFirmware(image);
        }
        @JavascriptInterface public void addRepository(String url, boolean bootloader) {
            worker.execute(() -> {
                try {
                    repositories.add(url, bootloader);
                    publishRepositories();
                    js("window.onRepositoryStatus('Repository saved.')");
                } catch (Exception error) {
                    js("window.onRepositoryStatus(" + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }
        @JavascriptInterface public void removeRepository(String url) {
            worker.execute(() -> {
                try {
                    repositories.remove(url);
                    publishRepositories();
                    js("window.onRepositoryStatus('Repository removed.')");
                } catch (Exception error) {
                    js("window.onRepositoryStatus(" + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }
        @JavascriptInterface public void openRepository(String url) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ReleaseRepositories.parse(url).url())));
                } catch (IllegalArgumentException | ActivityNotFoundException error) {
                    failure("repositories.open", error);
                    js("window.onRepositoryStatus('Could not open GitHub.')");
                }
            });
        }
        @JavascriptInterface public void repositoryAssets(int request, String url, boolean refresh) {
            worker.execute(() -> {
                try {
                    if (library == null) throw new IOException("Firmware library unavailable.");
                    var assets = repositories.assets(url, refresh, library);
                    log.add("repositories", "UF2 assets=" + assets.getJSONArray("files").length() +
                            " repository=" + ReleaseRepositories.parse(url).repository());
                    js("window.onRepositoryAssets(" + request + "," + assets + ",null)");
                } catch (Exception error) {
                    failure("repositories", error);
                    js("window.onRepositoryAssets(" + request + ",null," + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface public void downloadRepositoryAsset(int request, String url, String tag, String id, String digest) {
            worker.execute(() -> {
                try {
                    if (library == null) throw new IOException("Firmware library unavailable.");
                    var image = repositories.download(url, tag, Long.parseLong(id), digest, library);
                    log.add("repositories", "Downloaded UF2 " + image.getString("name"));
                    js("window.onRepositoryDownload(" + request + "," + image + ",null)");
                } catch (Exception error) {
                    failure("repositories.download", error);
                    js("window.onRepositoryDownload(" + request + ",null," + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface public void saveRepositoryAsset(String name, String sha, int size) {
            worker.execute(() -> {
                try {
                    if (library == null) throw new IOException("Firmware library unavailable.");
                    showSaveFirmware(repositories.loadLocal(name, sha, size, library));
                } catch (Exception error) {
                    failure("repositories.export", error);
                    js("window.onRepositoryStatus(" + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface public void removeRepositoryAsset(int request, String sha) {
            worker.execute(() -> {
                try {
                    repositories.removeLocal(sha);
                    js("window.onRepositoryRemoval(" + request + ",null)");
                } catch (Exception error) {
                    failure("repositories.remove", error);
                    js("window.onRepositoryRemoval(" + request + "," + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface public void readBootloader(int id) {
            runOnUiThread(() -> {
                UsbDevice device = findDevice(id);
                if (device == null || !isMassStorage(device) || readingBootloader != null) return;
                readingBootloader = device;
                log.add("bootloader", "INFO_UF2 requested deviceId=" + id);
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), READ_UF2_INFO);
            });
        }
        @JavascriptInterface public void openOfficial(String board) {
            runOnUiThread(() -> {
                try {
                    if (catalogue == null) return;
                    String url = catalogue.officialBoardUrl(board);
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    log.add("catalogue", "Opened official board page board=" + board);
                } catch (IllegalArgumentException | ActivityNotFoundException error) {
                    failure("catalogue.open", error);
                    js("window.onAssetStatus('Could not open the board page.')");
                }
            });
        }
        @JavascriptInterface public void otaFix(int id, String board, String filename) {
            runOnUiThread(() -> {
                var checked = bootloaderInspection;
                if (catalogue == null || !MeshCoreCatalogue.safeName(filename)) return;
                boolean attachedCheck = checked != null && checked.device().equals(findDevice(id));
                boolean matchingStock = attachedCheck && checked.info().status().equals("absent") &&
                        checked.info().matchesBoard(board) && checked.info().matchesFix(filename);
                if (!matchingStock && (attachedCheck || !MeshCoreCatalogue.RAK_1W.equals(board))) return;
                var files = catalogue.otaFixes(board);
                for (int i = 0; i < files.length(); i++) if (filename.equals(files.optString(i))) {
                    log.add("bootloader", "Opening official OTAFix download " + filename);
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                                "https://flasher.meshcore.io/firmware/" + filename)));
                    } catch (ActivityNotFoundException error) {
                        failure("bootloader.download", error);
                        publishDevice("Android has no browser to open the OTAFix download.");
                    }
                    return;
                }
            });
        }
        @JavascriptInterface public String connect(int id) {
            try { return openPort(id); } catch (Exception error) { failure("serial.open", error); return "USB open failed: " + error.getMessage(); }
        }
        @JavascriptInterface public void close() { closePort("protocol finished"); }
        @JavascriptInterface public String signals(int rts, int dtr) {
            try {
                var serial = port;
                if (serial == null) throw new IOException("Port closed");
                if (dtr >= 0) serial.setDTR(dtr != 0);
                if (rts >= 0) serial.setRTS(rts != 0);
                log.add("serial.signal", "requested RTS=" + rts + " DTR=" + dtr);
                return "";
            } catch (Exception error) { failure("serial.signal", error); return "USB signal failed: " + error.getMessage(); }
        }
        @JavascriptInterface public String write(String encoded) {
            long started = SystemClock.elapsedRealtime();
            try {
                var serial = port;
                if (serial == null) throw new IOException("Port closed");
                byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
                serial.write(data, 4000);
                log.add("serial.tx", "bytes=" + data.length + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                return "";
            } catch (Exception error) { failure("serial.tx", error); return "USB write failed: " + error.getMessage(); }
        }
        @JavascriptInterface public String readData() {
            synchronized (incoming) {
                if (incoming.size() == 0) return "";
                var result = Base64.encodeToString(incoming.toByteArray(), Base64.NO_WRAP);
                incoming.reset();
                return result;
            }
        }
        @JavascriptInterface public String portError() {
            return port == null ? "USB detached" : readError == null ? "" : readError;
        }
        @JavascriptInterface public String image() {
            var image = staged;
            return image == null ? "" : Base64.encodeToString(image.bytes(), Base64.NO_WRAP);
        }
        @JavascriptInterface public void finished(boolean success, String reason) {
            log.add("job", "Completed success=" + success + " " + reason);
            closePort("job ended");
            staged = null;
            approved = false;
        }
        @JavascriptInterface public void diagnostic(String stage, String message) { log.add(stage, message); }
        @JavascriptInterface public String logTail() {
            try { return log.tail(); } catch (IOException error) { failure("log", error); return "Log is unavailable."; }
        }
        @JavascriptInterface public void export() { runOnUiThread(() -> exportLog()); }
    }
}
