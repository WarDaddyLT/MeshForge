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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String DIRECT_UPDATE_BOARD = MeshCoreCatalogue.M5;
    private static final String PERMISSION = "dev.meshforge.fieldflasher.USB_PERMISSION";
    private static final int EXPORT = 1;
    private static final int SAVE_APP = 3;
    private static final int PICK_LOCAL_FIRMWARE = 4;
    private static final int PICK_LIBRARY_FIRMWARE = 5;
    private String localBoard;
    private volatile FirmwareLibrary.Image localImage;
    private ReleaseRepositories repositories;
    private FirmwareLibrary.Image exportingImage;
    private volatile BootloaderInspection bootloaderInspection;
    private record BootloaderInspection(UsbDevice device, BootloaderInfo info) {}
    private record DfuTransfer(FirmwareSelection selection, FirmwareLibrary.Image image) {}
    private volatile DfuTransfer pendingDfu;
    private boolean dfuTouchAttempted;
    private boolean dfuTouchInProgress;
    private boolean dfuWaitScheduled;
    private boolean permissionForDfu;
    private volatile boolean dfuWriting;
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService repositoryMetadataWorker = Executors.newSingleThreadExecutor();
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream(32768);
    private WebView view;
    private volatile float systemTopInset;
    private DiagnosticLog log;
    private FlashJournal journal;
    private FirmwareLibrary library;
    private MeshCoreCatalogue catalogue;
    private UsbManager usb;
    private volatile UsbSerialPort port;
    private volatile UsbDevice activeDevice;
    private volatile String readError;
    private volatile FirmwareLibrary.Image staged;
    private volatile boolean approved;
    private volatile UsbDevice approvedDevice;
    private record Inspection(UsbDevice device, MeshCoreIdentity.Result identity) {}
    private volatile Inspection inspection;
    private int pendingPermission = -1;
    private record FlashReview(UsbDevice device, boolean probeOnly, FirmwareLibrary.Image image) {}
    private record FirmwareSelection(int deviceId, String board, String source, String role, String tag, String name,
                                     String sha, String assetId) {}
    private FlashReview pendingFlashPermission;
    private record RuntimeCheck(UsbDevice original, Set<UsbDevice> initial, JSONObject image, long deadline) {}
    private RuntimeCheck runtimeCheck;
    private UsbDevice runtimePermission;
    private volatile UsbDevice flashRuntimeOriginal;
    private volatile Set<UsbDevice> flashRuntimeDevices = Set.of();
    private int usbScan;
    private boolean permissionForRepeater;
    private volatile boolean repeaterBusy;
    private volatile RepeaterSession repeaterSession;
    private record RepeaterSession(UsbDevice device, RepeaterCli.Snapshot snapshot) {}
    private record RepeaterChanges(Map<RepeaterCli.Field, String> fields, Map<String, Integer> acl,
                                   RepeaterTables.Regions regions) {}

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) beginUsbTrace(device, "USB_DEVICE_ATTACHED");
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
                var flash = pendingFlashPermission;
                pendingFlashPermission = null;
                if (runtimePermission != null) {
                    var candidate = runtimePermission;
                    runtimePermission = null;
                    if (granted && candidate.equals(device) && runtimeCheck != null) probeRuntime(runtimeCheck, device);
                    else finishRuntime(runtimeCheck, new FirmwareRuntime.Result("unconfirmed", "USB access was declined."));
                } else if (flash != null) {
                    if (granted && flash.device().equals(device)) prepareFlash(flash);
                    else flashFailed("USB permission was declined or the selected device changed.");
                } else if (permissionForDfu) {
                    permissionForDfu = false;
                    if (granted && pendingDfu != null) {
                        if (isMassStorage(device)) beginDfuWrite(device);
                        else requestDfuMode(device);
                    }
                    else failDfu("USB access was declined. MeshForge could not request DFU mode.");
                } else if (permissionForRepeater) {
                    permissionForRepeater = false;
                    if (granted) readRepeater(device);
                    else finishRepeater("USB access was declined. Tap Read settings to try again.");
                } else if (granted) inspect(device);
                else publishDevice("USB permission was declined.");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                var inspected = inspection;
                if (inspected != null && inspected.device().equals(device)) inspection = null;
                var bootloader = bootloaderInspection;
                if (bootloader != null && bootloader.device().equals(device)) bootloaderInspection = null;
                if (device != null && device.getDeviceId() == pendingPermission) {
                    pendingPermission = -1;
                    if (device.equals(runtimePermission)) runtimePermission = null;
                    if (pendingFlashPermission != null) {
                        pendingFlashPermission = null;
                        flashFailed("The selected USB device disconnected.");
                    }
                    if (permissionForRepeater) {
                        permissionForRepeater = false;
                        finishRepeater("The repeater disconnected.");
                    }
                    if (permissionForDfu) {
                        permissionForDfu = false;
                        failDfu("The radio disconnected before MeshForge could request DFU mode.");
                    }
                }
                var session = repeaterSession;
                if (session != null && session.device().equals(device)) repeaterSession = null;
                if (device != null && device.equals(approvedDevice)) approvedDevice = null;
                if (device != null && device.equals(activeDevice)) {
                    closePort("active device detached");
                    js("window.onDetach()");
                }
                publishDevice(null);
                continueDfuTransfer();
            } else {
                publishDevice(null);
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) continueDfuTransfer();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        log = new DiagnosticLog(getFilesDir());
        journal = new FlashJournal(getSharedPreferences("flash-journal", MODE_PRIVATE));
        usb = getSystemService(UsbManager.class);
        repositories = new ReleaseRepositories(this);
        log.add("app", "Started version=0.1.16 Android=" + Build.VERSION.RELEASE + " API=" +
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
            var edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() |
                    WindowInsets.Type.ime());
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

    @Override protected void onResume() {
        super.onResume();
        publishDevice(null);
    }

    private void handleIntent(Intent intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            beginUsbTrace(device, "activity attachment");
            describe("activity attachment", device);
            publishDevice(null);
        }
    }

    private void beginUsbTrace(UsbDevice device, String trigger) {
        if (device == null || log == null) return;
        String key = String.format(Locale.US, "%04x:%04x:%s", device.getVendorId(), device.getProductId(),
                device.getProductName() == null ? "" : device.getProductName());
        String details = String.format(Locale.US, "deviceId=%d vid=%04x pid=%04x product=%s",
                device.getDeviceId(), device.getVendorId(), device.getProductId(), device.getProductName());
        if (log.hasUsbSession() && pendingDfu != null) {
            log.add("trace", "USB re-enumeration during active operation trigger=" + trigger + " " + details);
        } else {
            log.beginUsbSession(trigger, key, details);
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
                beginUsbTrace(connected, "initial inventory");
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

    private void scanUsb(int scan, int attempt) {
        if (scan != usbScan || isDestroyed()) return;
        int count = usb.getDeviceList().size();
        log.add("usb.scan", "attempt=" + (attempt + 1) + " deviceCount=" + count);
        if (count == 0 && attempt < 2) {
            if (attempt == 0) publishDevice("Checking for USB devices…");
            view.postDelayed(() -> scanUsb(scan, attempt + 1), 350);
            return;
        }
        describe("initial inventory", null);
        boolean hostFeature = getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        publishDevice(count > 0 ? "USB devices detected: " + count + "." : hostFeature ?
                "Android sees no USB devices. Connect the radio with a USB data cable or OTG adapter and try again." :
                "Android reports that this phone lacks USB host support.");
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
        if (isM5Bridge(device)) return "WCH USB serial";
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
                        .put("reportedRole", identity != null ? identity.role() : "")
                        .put("flashSerial", flashDriver(device) != null)
                        .put("serial", serialDriver(device) != null && !isMassStorage(device))
                        .put("storage", isMassStorage(device))
                        .put("bootloader", info == null ? JSONObject.NULL : new JSONObject()
                                .put("version", info.version()).put("id", info.boardId()).put("status", info.status())
                                .put("family", info.fixFamily()).put("model", info.model()).put("softDevice", info.softDevice())
                                .put("sharedBoardId", info.sharedBoardId())
                                .put("boards", checkedBoards).put("fixes", checkedFixes)));
            } catch (JSONException error) {
                failure("usb.inventory", error);
            }
        }
        js("window.onUsb?.(" + inventory + "," + JSONObject.quote(status == null ? "" : status) + ")");
    }

    private String boardOf(UsbDevice device, MeshCoreIdentity.Result identity) {
        if (catalogue == null || isMassStorage(device)) return "";
        String board = identity != null ? catalogue.match(identity.model()) : null;
        if (board == null) board = catalogue.match(device.getProductName());
        return board == null ? "" : board;
    }

    private void connectUsb(int id) {
        UsbDevice device = findDevice(id);
        if (device == null || approved || repeaterBusy) {
            publishDevice("The USB device is unavailable.");
            return;
        }
        if (usb.hasPermission(device)) {
            inspect(device);
            return;
        }
        requestUsbPermission(device, false);
    }

    private void requestUsbPermission(UsbDevice device, boolean forRepeater) {
        if (pendingPermission >= 0) return;
        pendingPermission = device.getDeviceId();
        permissionForRepeater = forRepeater;
        var intent = new Intent(PERMISSION).setPackage(getPackageName());
        var reply = PendingIntent.getBroadcast(this, device.getDeviceId(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        log.add("permission", "Requesting USB access id=" + pendingPermission);
        try {
            usb.requestPermission(device, reply);
        } catch (Exception error) {
            pendingPermission = -1;
            permissionForRepeater = false;
            failure("permission", error);
            if (runtimePermission != null) {
                runtimePermission = null;
                finishRuntime(runtimeCheck, new FirmwareRuntime.Result("unconfirmed", "Could not request USB access after reset."));
            } else if (pendingFlashPermission != null) {
                pendingFlashPermission = null;
                flashFailed("Could not request USB access for flashing.");
            } else if (permissionForDfu) {
                permissionForDfu = false;
                failDfu("Could not request USB access to enter DFU mode.");
            } else if (forRepeater) finishRepeater("Could not request USB access.");
            else publishDevice("Could not request USB access.");
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
                boolean storage = isMassStorage(device);
                if (storage) inspectBootloader(device);
                var identity = storage ? null : queryIdentity(device);
                if (!device.equals(findDevice(device.getDeviceId()))) throw new IOException("The USB device detached.");
                inspection = new Inspection(device, identity);
                describe("connected descriptors", device);
                publishDevice(identity != null ? "MeshCore device information read." :
                        storage ? "UF2 bootloader information read directly over USB." :
                        "USB device detected. Select the board manually if you know the exact model.");
            } catch (Exception error) {
                failure("usb.connect", error);
                publishDevice(error.getMessage());
            }
        });
    }

    private static UsbSerialDriver serialDriver(UsbDevice device) {
        return isM5Bridge(device) ? new Ch34xSerialDriver(device) :
                UsbSerialProber.getDefaultProber().probeDevice(device);
    }

    private static UsbSerialDriver flashDriver(UsbDevice device) {
        if (device == null || isMassStorage(device)) return null;
        UsbSerialDriver driver = serialDriver(device);
        if (driver == null || driver.getPorts().size() != 1) return null;
        boolean nativeEsp = device.getVendorId() == 0x303a && device.getProductId() == 0x1001;
        return nativeEsp || driver instanceof Ch34xSerialDriver || driver instanceof Cp21xxSerialDriver ||
                driver instanceof FtdiSerialDriver ? driver : null;
    }

    private void startRuntime(UsbDevice original, Set<UsbDevice> initial, JSONObject image) {
        if (isDestroyed() || isFinishing()) return;
        var check = new RuntimeCheck(original, initial, image, SystemClock.elapsedRealtime() + 20_000);
        runtimeCheck = check;
        view.postDelayed(() -> tryRuntime(check), 1_000);
        view.postDelayed(() -> finishRuntime(check, new FirmwareRuntime.Result("unconfirmed",
                "No verifiable MeshCore response over USB. Check the running firmware on the radio.")), 20_000);
    }

    private void tryRuntime(RuntimeCheck check) {
        if (runtimeCheck != check || runtimePermission != null || isDestroyed()) return;
        var candidates = usb.getDeviceList().values().stream()
                .filter(device -> !isMassStorage(device) && serialDriver(device) != null)
                .filter(device -> device.equals(check.original()) || !check.initial().contains(device))
                .toList();
        if (candidates.size() != 1 || pendingPermission >= 0) {
            view.postDelayed(() -> tryRuntime(check), 800);
            return;
        }
        var device = candidates.get(0);
        if (!usb.hasPermission(device)) {
            runtimePermission = device;
            requestUsbPermission(device, false);
            return;
        }
        probeRuntime(check, device);
    }

    private void probeRuntime(RuntimeCheck check, UsbDevice device) {
        worker.execute(() -> {
            var identity = device.equals(findDevice(device.getDeviceId())) ? queryIdentity(device) : null;
            view.post(() -> {
                if (runtimeCheck != check) return;
                if (identity == null || !device.equals(findDevice(device.getDeviceId()))) {
                    if (SystemClock.elapsedRealtime() < check.deadline()) view.postDelayed(() -> tryRuntime(check), 800);
                    return;
                }
                var details = check.image();
                String board = catalogue == null ? null : catalogue.match(identity.model());
                String expectedVersion = FirmwareRuntime.version(details.optString("tag"));
                if (expectedVersion.isEmpty()) expectedVersion = FirmwareRuntime.version(details.optString("name"));
                finishRuntime(check, FirmwareRuntime.compare(details.optString("board"), details.optString("role"),
                        expectedVersion, board == null ? "" : board, identity.role(), identity.version()));
            });
        });
    }

    private void finishRuntime(RuntimeCheck check, FirmwareRuntime.Result result) {
        if (check == null || runtimeCheck != check) return;
        runtimeCheck = null;
        if (runtimePermission != null && pendingPermission == runtimePermission.getDeviceId()) pendingPermission = -1;
        runtimePermission = null;
        log.add("flash.runtime", result.status() + " " + result.message());
        js("window.onRuntimeVerification(" + JSONObject.quote(result.status()) + "," + JSONObject.quote(result.message()) + ")");
        if (result.status().equals("confirmed")) journal.clear();
        publishRecovery();
    }

    private MeshCoreIdentity.Result queryIdentity(UsbDevice device) {
        UsbSerialDriver driver = serialDriver(device);
        if (driver == null || driver.getPorts().size() != 1) return null;
        UsbDeviceConnection connection = usb.openDevice(device);
        if (connection == null) return null;
        UsbSerialPort serial = driver.getPorts().get(0);
        try {
            serial.open(connection);
            serial.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            if (serial instanceof CdcAcmSerialDriver.CdcAcmSerialPort) serial.setDTR(true);
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
        UsbDevice device = findDevice(id);
        if (device == null || !device.equals(approvedDevice) || !usb.hasPermission(device)) {
            throw new IOException("The approved radio is disconnected or permission changed");
        }
        UsbSerialDriver driver = flashDriver(device);
        if (driver == null) throw new IOException("The selected USB connection does not support ESP flashing.");
        log.add("serial", "Opening " + driver.getClass().getSimpleName() + " id=" + id);
        var connection = usb.openDevice(device);
        if (connection == null) throw new IOException("Android openDevice returned null");
        UsbSerialPort serial = driver.getPorts().get(0);
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

    private void beginReadRepeater(int id) {
        UsbDevice device = findDevice(id);
        if (repeaterBusy) return;
        repeaterSession = null;
        if (approved || pendingPermission >= 0 || device == null || isMassStorage(device) ||
                serialDriver(device) == null) {
            finishRepeater("Select an available USB serial device and try again.");
            return;
        }
        repeaterBusy = true;
        if (usb.hasPermission(device)) readRepeater(device);
        else requestUsbPermission(device, true);
    }

    private void readRepeater(UsbDevice device) {
        js("window.onRepeaterReading()");
        worker.execute(() -> {
            try {
                var session = withRepeater(device, RepeaterCli::read);
                publishRepeater(new RepeaterSession(device, session), "Settings read from the radio.");
            } catch (Exception error) {
                log.add("repeater.read", "Failed type=" + error.getClass().getSimpleName());
                finishRepeater(repeaterError(error));
            }
        });
    }

    private void confirmRepeater(int id, String input) {
        if (repeaterBusy) return;
        var session = repeaterSession;
        if (approved || session == null || session.device().getDeviceId() != id || !repeaterAttached(session.device())) {
            finishRepeater("Read settings from the selected radio before saving.");
            return;
        }
        var changes = new LinkedHashMap<RepeaterCli.Field, String>();
        Map<String, Integer> acl = null;
        RepeaterTables.Regions regions = null;
        try {
            if (input.length() > 30000) throw new IOException("The settings are too large.");
            var values = new JSONObject(input);
            for (var field : RepeaterCli.Field.values()) {
                if (!values.has(field.key) || !session.snapshot().settings().containsKey(field)) continue;
                String value = field.validate(values.getString(field.key));
                if (!value.equals(session.snapshot().settings().get(field))) changes.put(field, value);
            }
            if (values.has("acl")) {
                var edited = RepeaterTables.aclInput(values.getJSONArray("acl"), session.snapshot().acl());
                if (!edited.equals(session.snapshot().acl())) acl = edited;
            }
            if (values.has("regions")) {
                var edited = RepeaterTables.regionInput(values.getJSONObject("regions"), session.snapshot().regions());
                if (!edited.equals(session.snapshot().regions())) regions = edited;
            }
            var current = session.snapshot().settings();
            String name = changes.getOrDefault(RepeaterCli.Field.NAME, current.get(RepeaterCli.Field.NAME));
            String lat = changes.getOrDefault(RepeaterCli.Field.LAT, current.getOrDefault(RepeaterCli.Field.LAT, "0"));
            String lon = changes.getOrDefault(RepeaterCli.Field.LON, current.getOrDefault(RepeaterCli.Field.LON, "0"));
            if ((changes.containsKey(RepeaterCli.Field.NAME) || changes.containsKey(RepeaterCli.Field.LAT) ||
                    changes.containsKey(RepeaterCli.Field.LON)) && (!lat.equals("0") || !lon.equals("0")) &&
                    name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 24) {
                throw new IOException("Use a node name of up to 24 UTF-8 bytes when sharing coordinates.");
            }
        } catch (IOException | JSONException error) {
            publishRepeater(session, repeaterError(error), true);
            return;
        }
        if (changes.isEmpty() && acl == null && regions == null) {
            publishRepeater(session, "There are no changes to save.", true);
            return;
        }
        repeaterBusy = true;
        var labels = new ArrayList<String>();
        changes.keySet().forEach(field -> labels.add(field.label));
        if (acl != null) labels.add("Access control list");
        if (regions != null) labels.add("Region settings");
        String message = "Save these settings to the connected radio?\n\n" + String.join("\n", labels);
        if (changes.containsKey(RepeaterCli.Field.RADIO)) message += "\n\nRestart the radio to apply LoRa changes.";
        var plan = new RepeaterChanges(changes, acl, regions);
        new AlertDialog.Builder(this)
                .setTitle("Save radio settings")
                .setMessage(message)
                .setNegativeButton("Cancel", (dialog, button) -> publishRepeater(session, "Save canceled.", true))
                .setOnCancelListener(dialog -> publishRepeater(session, "Save canceled.", true))
                .setPositiveButton("Save", (dialog, button) -> saveRepeater(session, plan))
                .show();
    }

    private void saveRepeater(RepeaterSession session, RepeaterChanges plan) {
        js("window.onRepeaterSaving()");
        worker.execute(() -> {
            var fields = plan.fields();
            int total = fields.size() + (plan.regions() == null ? 0 : 1) + (plan.acl() == null ? 0 : 1);
            var verified = new ArrayList<String>();
            boolean[] attemptedWrite = {false};
            try {
                var updated = withRepeater(session.device(), cli -> {
                    var current = cli.read();
                    if (!current.sameConfiguration(session.snapshot())) {
                        throw new IOException("The radio or its settings changed. Read settings and review them again.");
                    }
                    for (var change : fields.entrySet()) {
                        if (!repeaterAttached(session.device())) throw new IOException("The radio disconnected.");
                        attemptedWrite[0] = true;
                        cli.write(change.getKey(), change.getValue());
                        verified.add(change.getKey().label);
                    }
                    if (plan.regions() != null) {
                        if (!repeaterAttached(session.device())) throw new IOException("The radio disconnected.");
                        attemptedWrite[0] = true;
                        RepeaterTables.writeRegions(cli, current.regions(), plan.regions());
                        verified.add("Regions");
                    }
                    if (plan.acl() != null) {
                        if (!repeaterAttached(session.device())) throw new IOException("The radio disconnected.");
                        attemptedWrite[0] = true;
                        RepeaterTables.writeAcl(cli, current.acl(), plan.acl());
                        verified.add("Access control");
                    }
                    return cli.read();
                });
                log.add("repeater.save", "Verified fields=" + verified.size());
                publishRepeater(new RepeaterSession(session.device(), updated),
                        "Saved and verified " + verified.size() + " setting(s)." +
                        (fields.containsKey(RepeaterCli.Field.RADIO) ? " Restart the radio to apply LoRa changes." : ""));
            } catch (Exception error) {
                log.add("repeater.save", "Stopped type=" + error.getClass().getSimpleName() +
                        " attemptedWrite=" + attemptedWrite[0] + " verifiedFields=" + verified.size());
                String status = repeaterError(error);
                if (attemptedWrite[0]) status += " Verified " + verified.size() + " of " + total +
                        " changes. Read settings again to check the current values.";
                finishRepeater(status);
            }
        });
    }

    private void showRadioPrivateKey(int id) {
        var session = repeaterSession;
        if (repeaterBusy || approved || session == null || session.device().getDeviceId() != id) return;
        repeaterBusy = true;
        worker.execute(() -> {
            String key = null;
            try {
                key = withRepeater(session.device(), cli -> {
                    String observed = cli.setting("public.key");
                    if (!session.snapshot().key().equalsIgnoreCase(observed)) throw new IOException("The radio changed.");
                    String value = cli.setting("prv.key");
                    if (value == null || !value.matches("[a-fA-F0-9]{128}")) throw new IOException("The radio did not return a private key.");
                    return value;
                });
            } catch (Exception error) {
                log.add("repeater.identity", "Private key read failed type=" + error.getClass().getSimpleName());
            }
            String privateKey = key;
            runOnUiThread(() -> {
                if (privateKey == null || !repeaterAttached(session.device())) {
                    publishRepeater(session, "The private key is unavailable for this radio.", true);
                    return;
                }
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Radio private key")
                        .setMessage(privateKey).setPositiveButton("Close", null)
                        .setOnDismissListener(ignored -> publishRepeater(session, "", true)).create();
                if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                dialog.show();
            });
        });
    }

    private interface RepeaterOperation<T> { T run(RepeaterCli cli) throws IOException; }

    private <T> T withRepeater(UsbDevice device, RepeaterOperation<T> operation) throws IOException {
        if (approved || !repeaterAttached(device)) throw new IOException("The selected USB device is unavailable.");
        UsbSerialDriver driver = serialDriver(device);
        if (driver == null || driver.getPorts().size() != 1) {
            throw new IOException("The repeater needs one supported USB serial port.");
        }
        UsbDeviceConnection connection = usb.openDevice(device);
        if (connection == null) throw new IOException("Android could not open the selected USB device.");
        UsbSerialPort serial = driver.getPorts().get(0);
        try {
            serial.open(connection);
            serial.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            if (serial instanceof CdcAcmSerialDriver.CdcAcmSerialPort) {
                serial.setDTR(true);
                log.add("repeater.serial", "CDC DTR asserted");
            }
            log.add("repeater.serial", "Opened driver=" + driver.getClass().getSimpleName() + " baud=115200 8N1");
            return operation.run(new RepeaterCli(serial, log));
        } finally {
            try { serial.close(); } catch (IOException error) { connection.close(); }
            log.add("repeater.serial", "Closed");
        }
    }

    private boolean repeaterAttached(UsbDevice device) {
        return !isDestroyed() && device.equals(findDevice(device.getDeviceId())) && usb.hasPermission(device);
    }

    private static String repeaterError(Exception error) {
        return error instanceof IOException && error.getMessage() != null ?
                error.getMessage() : "Could not communicate with the radio over USB.";
    }

    private void finishRepeater(String message) { publishRepeater(null, message); }

    private void publishRepeater(RepeaterSession session, String message) {
        publishRepeater(session, message, false);
    }

    private void publishRepeater(RepeaterSession session, String message, boolean keepEdits) {
        runOnUiThread(() -> {
            repeaterBusy = false;
            repeaterSession = session != null && repeaterAttached(session.device()) ? session : null;
            JSONObject result = null;
            if (repeaterSession != null) {
                try {
                    var settings = new JSONObject();
                    var snapshot = session.snapshot();
                    for (var field : snapshot.settings().entrySet()) {
                        if (field.getKey() == RepeaterCli.Field.GUEST) {
                            settings.put(field.getKey().key, "");
                            continue;
                        }
                        settings.put(field.getKey().key, field.getValue());
                    }
                    result = new JSONObject().put("device", session.device().getDeviceId())
                            .put("version", snapshot.version()).put("settings", settings)
                            .put("role", snapshot.role()).put("publicKey", snapshot.key())
                            .put("clock", snapshot.clock()).put("guestSet", !snapshot.settings().getOrDefault(
                                    RepeaterCli.Field.GUEST, "").isEmpty());
                    if (snapshot.acl() != null) result.put("acl", new JSONObject(snapshot.acl()));
                    if (snapshot.regions() != null) result.put("regions", snapshot.regions().json());
                } catch (JSONException error) {
                    repeaterSession = null;
                }
            }
            js("window.onRepeater(" + result + "," + JSONObject.quote(message) + "," + keepEdits + ")");
        });
    }

    private void publishRecovery() {
        try {
            js("window.onFlashRecovery(" + journal.recovery() + ")");
        } catch (JSONException error) {
            failure("flash.recovery", error);
        }
    }

    private void cancelFlash() {
        journal.clear();
        publishRecovery();
        js("window.onFlashCancelled()");
    }

    private void flashFailed(String message) {
        log.add("flash.outcome", "Failed before or during flash: " + message);
        log.endUsbSession(false, message);
        publishRecovery();
        js("window.onComplete(false," + JSONObject.quote(message) + ")");
    }

    private void beginFlashRequest(String json) {
        log.add("flash.request", "Received firmware flash request jsonLength=" + (json == null ? 0 : json.length()));
        try {
            if (json == null || json.length() > 4096) throw new IOException("Invalid flash selection.");
            var values = new JSONObject(json);
            UsbDevice device = findDevice(values.getInt("device"));
            if (device == null || approved || repeaterBusy || library == null) {
                flashFailed("The selected USB device is unavailable.");
                return;
            }
            beginUsbTrace(device, "flash request");
            var selection = new FirmwareSelection(values.optInt("device", -1), values.getString("board"), values.getString("source"),
                    values.optString("role"), values.optString("tag"), values.getString("name"),
                    values.optString("sha"), values.optString("assetId"));
            journal.start(selection.board(), selection.name(), selection.sha(), false);
            log.add("flash.selection", "deviceId=" + selection.deviceId() + " board=" + selection.board() +
                    " source=" + selection.source() + " role=" + selection.role() + " tag=" + selection.tag() +
                    " name=" + selection.name() + " sha256=" + selection.sha());
            worker.execute(() -> {
                try {
                    var image = resolveFlashImage(selection);
                    journal.advance(FlashJournal.Stage.STAGED, image.details().optString("sha256"));
                    log.add("flash.prepare", "Resolved direct image bytes=" + image.bytes().length +
                            " sha256=" + image.details().optString("sha256"));
                    view.post(() -> beginFlashReview(new FlashReview(device, false, image)));
                } catch (Exception error) {
                    failure("flash.prepare", error);
                    flashFailed(error.getMessage());
                }
            });
        } catch (IOException | JSONException error) {
            flashFailed(error.getMessage());
        }
    }

    private void beginDfuTransfer(String json) {
        log.add("dfu.request", "Received UF2 install request jsonLength=" + (json == null ? 0 : json.length()));
        try {
            if (json == null || json.length() > 4096 || library == null || catalogue == null) {
                throw new IOException("Invalid DFU selection.");
            }
            var values = new JSONObject(json);
            var selection = new FirmwareSelection(values.optInt("device", -1), values.getString("board"), values.getString("source"),
                    values.optString("role"), values.optString("tag"), values.getString("name"),
                    values.optString("sha"), values.optString("assetId"));
            if (!catalogue.isNrf(selection.board()) || MeshCoreCatalogue.uf2Start(selection.board()) == 0 ||
                    !selection.name().toLowerCase(Locale.ROOT).endsWith(".uf2") ||
                    !(Set.of("official", "save", "solo", "local", "library").contains(selection.source()) ||
                            selection.source().startsWith("https://"))) {
                throw new IOException("The selected board and file require a supported UF2 application write method.");
            }
            if (pendingDfu != null) throw new IOException("A UF2 update is already waiting for the radio.");
            journal.start(selection.board(), selection.name(), selection.sha(), true);
            log.add("dfu.selection", "deviceId=" + selection.deviceId() + " board=" + selection.board() +
                    " source=" + selection.source() + " role=" + selection.role() + " tag=" + selection.tag() +
                    " name=" + selection.name() + " sha256=" + selection.sha());
            worker.execute(() -> {
                try {
                    FirmwareLibrary.Image image = resolveDfuImage(selection);
                    journal.advance(FlashJournal.Stage.STAGED, image.details().optString("sha256"));
                    log.add("dfu.prepare", "Verified UF2 bytes=" + image.bytes().length +
                            " sha256=" + image.details().optString("sha256"));
                    view.post(() -> confirmDfuTransfer(new DfuTransfer(selection, image)));
                } catch (Exception error) {
                    failure("dfu.prepare", error);
                    log.endUsbSession(false, error.getMessage());
                    publishRecovery();
                    js("window.onDfuTransfer(" + JSONObject.quote(error.getMessage()) + ",true,false)");
                }
            });
        } catch (Exception error) {
            failure("dfu.prepare", error);
            log.endUsbSession(false, error.getMessage());
            publishRecovery();
            js("window.onDfuTransfer(" + JSONObject.quote(error.getMessage()) + ",true,false)");
        }
    }

    private void confirmDfuTransfer(DfuTransfer transfer) {
        if (isFinishing() || isDestroyed()) return;
        var selection = transfer.selection();
        js("window.onDfuTransfer('Firmware verified. Waiting for confirmation…',false)");
        new AlertDialog.Builder(this)
                .setTitle("Flash firmware")
                .setMessage(selection.board() + "\n" + selection.name() +
                        "\n\nMeshForge will check the bootloader identity before writing.")
                .setNegativeButton("Cancel", (dialog, which) -> cancelDfuConfirmation())
                .setOnCancelListener(dialog -> cancelDfuConfirmation())
                .setPositiveButton("Flash firmware", (dialog, which) -> {
                    pendingDfu = transfer;
                    dfuTouchAttempted = false;
                    dfuTouchInProgress = false;
                    dfuWaitScheduled = false;
                    continueDfuTransfer();
                })
                .show();
    }

    private void cancelDfuConfirmation() {
        log.endUsbSession(false, "UF2 update canceled before writing");
        cancelFlash();
    }

    private void continueDfuTransfer() {
        DfuTransfer transfer = pendingDfu;
        if (transfer == null || dfuWriting || dfuTouchInProgress || permissionForDfu || isDestroyed()) {
            log.add("dfu.state", "continue skipped pending=" + (transfer != null) + " writing=" + dfuWriting +
                    " touchInProgress=" + dfuTouchInProgress + " permissionPending=" + permissionForDfu +
                    " destroyed=" + isDestroyed());
            return;
        }
        long driveCount = usb.getDeviceList().values().stream().filter(MainActivity::isMassStorage).count();
        log.add("dfu.discovery", "Mass-storage device count=" + driveCount + " totalUsbDevices=" + usb.getDeviceList().size() +
                " touchAttempted=" + dfuTouchAttempted);
        if (driveCount > 1) {
            failDfu("Disconnect other USB storage devices so MeshForge can identify the radio bootloader.");
            return;
        }
        if (driveCount == 0) {
            if (dfuTouchAttempted) {
                waitForDfuDrive(transfer);
                return;
            }
            UsbDevice device;
            try {
                device = findDfuSerialDevice(transfer.selection());
            } catch (IOException error) {
                failDfu(error.getMessage());
                return;
            }
            if (device == null) {
                log.add("dfu.discovery", "No eligible USB serial device yet");
                waitForDfuDrive(transfer);
                return;
            }
            if (!usb.hasPermission(device)) {
                if (pendingPermission >= 0) {
                    failDfu("Finish the pending USB permission request, then start the DFU update again.");
                    return;
                }
                permissionForDfu = true;
                log.add("dfu.permission", "Requesting USB permission before 1200-baud DFU touch deviceId=" +
                        device.getDeviceId());
                js("window.onDfuTransfer('Allow USB access so MeshForge can request DFU mode…',false)");
                requestUsbPermission(device, false);
                return;
            }
            requestDfuMode(device);
            return;
        }
        UsbDevice drive = usb.getDeviceList().values().stream().filter(MainActivity::isMassStorage)
                .findFirst().orElse(null);
        if (drive == null) {
            failDfu("The radio's USB bootloader drive disappeared before installation.");
            return;
        }
        if (!usb.hasPermission(drive)) {
            if (pendingPermission >= 0) {
                failDfu("Finish the pending USB permission request, then start the DFU update again.");
                return;
            }
            permissionForDfu = true;
            log.add("dfu.permission", "Requesting USB permission for bootloader drive deviceId=" + drive.getDeviceId());
            js("window.onDfuTransfer('Allow USB access so MeshForge can write firmware directly to the radio…',false)");
            requestUsbPermission(drive, false);
            return;
        }
        beginDfuWrite(drive);
    }

    private void waitForDfuDrive(DfuTransfer transfer) {
        String instructions = catalogue.dfuEntry(transfer.selection().board());
        String hint = "Waiting for the radio’s UF2 bootloader drive. " + instructions;
        js("window.onDfuTransfer(" + JSONObject.quote(hint) + ",false)");
        if (dfuWaitScheduled) return;
        dfuWaitScheduled = true;
        view.postDelayed(() -> {
            if (pendingDfu == transfer && !dfuWriting && !dfuTouchInProgress && !permissionForDfu) {
                failDfu("The radio’s UF2 bootloader drive did not appear within one minute. " + instructions);
            }
        }, 60_000);
    }

    private void beginDfuWrite(UsbDevice device) {
        DfuTransfer transfer = pendingDfu;
        if (transfer == null || dfuWriting || !isMassStorage(device) ||
                !usb.hasPermission(device) || !device.equals(findDevice(device.getDeviceId()))) {
            failDfu("The radio's USB bootloader is no longer available for firmware installation.");
            return;
        }
        dfuWriting = true;
        log.add("dfu.install", "Starting direct USB UF2 transfer deviceId=" + device.getDeviceId() +
                " interfaces=" + device.getInterfaceCount());
        js("window.onDfuTransfer('Verifying the radio bootloader and writing UF2 directly over USB…',false)");
        worker.execute(() -> writeDfuTransfer(transfer, device));
    }

    private UsbDevice findDfuSerialDevice(FirmwareSelection selection) throws IOException {
        log.add("dfu.discovery", "Searching USB serial devices for board=" + selection.board() +
                " selectedDeviceId=" + selection.deviceId());
        var serialDevices = new ArrayList<UsbDevice>();
        var matchingDevices = new ArrayList<UsbDevice>();
        for (UsbDevice device : usb.getDeviceList().values()) {
            if (isMassStorage(device)) continue;
            UsbSerialDriver driver = serialDriver(device);
            if (driver == null || driver.getPorts().size() != 1) continue;
            serialDevices.add(device);
            var inspected = inspection;
            MeshCoreIdentity.Result identity = inspected != null && inspected.device().equals(device) ? inspected.identity() : null;
            String detectedBoard = boardOf(device, identity);
            if (selection.board().equals(detectedBoard)) matchingDevices.add(device);
        }
        if (selection.deviceId() >= 0) {
            UsbDevice selected = findDevice(selection.deviceId());
            if (selected != null && !serialDevices.contains(selected)) {
                throw new IOException("The selected USB device does not expose a supported serial interface.");
            }
            if (selected == null) return null;
            return selected;
        }
        if (matchingDevices.size() == 1) return matchingDevices.get(0);
        if (matchingDevices.size() > 1) {
            throw new IOException("More than one USB serial device matches this board. Disconnect the other radios.");
        }
        if (serialDevices.isEmpty()) return null;
        if (serialDevices.size() > 1) {
            throw new IOException("MeshForge cannot identify the target radio among multiple USB serial devices. Disconnect the other devices.");
        }
        UsbDevice device = serialDevices.get(0);
        var inspected = inspection;
        MeshCoreIdentity.Result identity = inspected != null && inspected.device().equals(device) ? inspected.identity() : null;
        String detectedBoard = boardOf(device, identity);
        if (!detectedBoard.isEmpty() && !selection.board().equals(detectedBoard)) {
            throw new IOException("The connected USB radio is " + detectedBoard + ", not " + selection.board() + ".");
        }
        return device;
    }

    private void requestDfuMode(UsbDevice device) {
        DfuTransfer transfer = pendingDfu;
        if (transfer == null || dfuTouchAttempted || dfuTouchInProgress) return;
        if (!device.equals(findDevice(device.getDeviceId())) || !usb.hasPermission(device)) {
            failDfu("The selected radio disconnected or USB access was lost before DFU entry.");
            return;
        }
        UsbSerialDriver driver = serialDriver(device);
        if (driver == null || driver.getPorts().size() != 1) {
            failDfu("The selected radio has no supported USB serial interface. " + catalogue.dfuEntry(transfer.selection().board()));
            return;
        }
        dfuTouchAttempted = true;
        dfuTouchInProgress = true;
        log.add("dfu.entry", "Starting 1200-baud touch deviceId=" + device.getDeviceId() +
                " board=" + transfer.selection().board());
        js("window.onDfuTransfer('Requesting DFU mode over USB (1200-baud touch)…',false)");
        worker.execute(() -> {
            UsbDeviceConnection connection = null;
            UsbSerialPort serial = driver.getPorts().get(0);
            try {
                connection = usb.openDevice(device);
                if (connection == null) throw new IOException("Android could not open the radio USB connection.");
                serial.open(connection);
                boolean hasDtr = serial.getSupportedControlLines().contains(UsbSerialPort.ControlLine.DTR);
                if (hasDtr) serial.setDTR(true);
                serial.setParameters(1200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                log.add("dfu.entry", "Requested USB DFU mode at 1200 baud board=" + transfer.selection().board() +
                        " deviceId=" + device.getDeviceId());
                Thread.sleep(100);
                if (hasDtr) serial.setDTR(false);
                serial.close();
                connection.close();
                connection = null;
                Thread.sleep(1500);
                view.post(() -> {
                    dfuTouchInProgress = false;
                    continueDfuTransfer();
                });
            } catch (Exception error) {
                try { serial.close(); } catch (Exception closeError) { if (connection != null) connection.close(); }
                if (connection != null) connection.close();
                failure("dfu.entry", error);
                view.post(() -> {
                    dfuTouchInProgress = false;
                    continueDfuTransfer();
                });
            }
        });
    }

    private void failDfu(String message) {
        log.add("dfu.outcome", "Failed: " + message);
        pendingDfu = null;
        dfuTouchInProgress = false;
        dfuWriting = false;
        permissionForDfu = false;
        log.endUsbSession(false, message);
        publishRecovery();
        js("window.onDfuTransfer(" + JSONObject.quote(message) + ",true,false)");
    }

    private FirmwareLibrary.Image resolveDfuImage(FirmwareSelection selection) throws Exception {
        log.add("dfu.resolve", "Locating selected repository asset board=" + selection.board() +
                " source=" + selection.source() + " tag=" + selection.tag() + " name=" + selection.name());
        FirmwareLibrary.Image image;
        if ("local".equals(selection.source())) {
            image = localImage;
            if (image == null || !"uf2".equals(image.details().optString("route")) ||
                    !selection.name().equals(image.details().optString("name")) ||
                    !selection.board().equals(image.details().optString("board")) ||
                    !selection.sha().equals(image.details().optString("sha256"))) {
                throw new IOException("Choose the local UF2 file again for this board.");
            }
        } else if ("library".equals(selection.source())) {
            image = resolveSavedUf2(selection);
        } else if (selection.source().startsWith("https://")) {
            if (!selection.sha().matches("[0-9a-f]{64}")) {
                throw new IOException("This UF2 release has no published SHA-256.");
            }
            var saved = cacheRepositorySelection(selection);
            image = new FirmwareLibrary.Image(saved.bytes(), new JSONObject()
                    .put("board", selection.board()).put("name", selection.name()).put("sha256", selection.sha()));
        } else {
            image = library.findCached(false, details -> matchesRelease(selection, details));
            if (image == null) {
                JSONObject option = findReleaseFile(selection.board(), selection.role(), selection.source(),
                        selection.tag(), selection.name());
                if (option.optBoolean("exportOnly")) throw new IOException("Choose a board-compatible UF2 image.");
                js("window.onDfuTransfer('Downloading and verifying the board-compatible UF2…')");
                JSONObject details = library.fetch(selection.source(), selection.board(), selection.role(), option);
                publishFirmware(details, true);
                image = library.findCached(false, item -> matchesRelease(selection, item));
            }
            if (image == null) throw new IOException("The selected UF2 is unavailable in the firmware library.");
        }
        if (Set.of("official", "save", "solo").contains(selection.source()) && !matchesRelease(selection, image.details())) {
            throw new IOException("The staged UF2 does not match the selected release.");
        }
        FirmwareLibrary.validateUf2(image.bytes(), MeshCoreCatalogue.uf2Start(selection.board()));
        log.add("dfu.resolve", "UF2 block/address validation passed start=0x" +
                Integer.toHexString(MeshCoreCatalogue.uf2Start(selection.board())));
        return image;
    }

    private FirmwareLibrary.Image resolveSavedUf2(FirmwareSelection selection) throws Exception {
        if (!selection.assetId().isEmpty()) {
            var image = library.findCached(false, entry -> matchesSavedSelection(selection, entry) &&
                    !entry.optBoolean("exportOnly"));
            if (image != null) return image;
        } else {
            var files = repositories.downloads();
            for (int i = 0; i < files.length(); i++) {
                var file = files.getJSONObject(i);
                if (!selection.sha().equals(file.optString("sha256")) || !selection.name().equals(file.optString("name")) ||
                        !(file.optBoolean("local") || file.optBoolean("checksumPublished"))) continue;
                var saved = repositories.loadLocal(selection.name(), selection.sha(), file.getLong("size"), library);
                return new FirmwareLibrary.Image(saved.bytes(), new JSONObject(saved.details().toString())
                        .put("board", selection.board()).put("tag", selection.tag()));
            }
        }
        throw new IOException("The selected offline image is unavailable for this board.");
    }

    private static boolean matchesSavedSelection(FirmwareSelection selection, JSONObject entry) {
        return selection.assetId().equals(entry.optString("cacheKey")) &&
                selection.sha().equals(entry.optString("sha256")) && selection.board().equals(entry.optString("board")) &&
                selection.name().equals(entry.optString("name")) && selection.tag().equals(entry.optString("tag"));
    }

    private FirmwareLibrary.Image resolveFlashImage(FirmwareSelection selection) throws Exception {
        log.add("flash.resolve", "Resolving board=" + selection.board() + " source=" + selection.source() +
                " role=" + selection.role() + " tag=" + selection.tag() + " name=" + selection.name());
        var cached = library.findCached(true, details -> matchesFlashSelection(selection, details));
        if (cached != null) {
            log.add("flash.resolve", "Using exact saved application SHA-256 " + cached.details().optString("sha256"));
            return cached;
        }
        if ("local".equals(selection.source()) || "library".equals(selection.source())) {
            throw new IOException("The selected offline image has no verified direct-write plan for this board.");
        }
        if (selection.source().startsWith("https://")) {
            cacheRepositorySelection(selection);
            throw new IOException("Firmware is saved in Library. MeshForge has no USB write method for this board and file combination.");
        }
        JSONObject option = findReleaseFile(selection.board(), selection.role(), selection.source(),
                selection.tag(), selection.name());
        log.add("flash.resolve", "Selected GitHub asset resolved id=" + option.optString("id") +
                " size=" + option.optLong("size"));
        if (option.optString("appTag").isEmpty() && cachedExportMatches(selection)) {
            throw new IOException("Firmware is available in Library. Use the selected board’s separate update procedure.");
        }
        js("window.onFlashDownload()");
        JSONObject details = library.fetch(selection.source(), selection.board(), selection.role(), option);
        publishFirmware(details, true);
        if (!details.getBoolean("direct")) {
            js("window.onFlashPrepared('Verified firmware saved to Library.')");
            throw new IOException("Firmware saved to Library. Use the selected board’s separate update procedure.");
        }
        // Resolve, validate and capture the exact selected image before USB access is requested.
        var image = library.findCached(true, item -> matchesFlashSelection(selection, item));
        if (image == null) throw new IOException("The exact selected image could not be verified.");
        log.add("flash.resolve", "Downloaded image matches exact selected release");
        return image;
    }

    private static boolean matchesFlashSelection(FirmwareSelection selection, JSONObject data) {
        String board = data.optString("board");
        String source = data.optString("source");
        String role = data.optString("role");
        if (!selection.board().equals(board) || !MeshCoreCatalogue.supportsDirectUpdate(board, source, role)) return false;
        if ("library".equals(selection.source())) return matchesSavedSelection(selection, data);
        if ("local".equals(selection.source()) || selection.source().startsWith("https://")) {
            return selection.sha().matches("[0-9a-f]{64}") && selection.sha().equals(data.optString("sha256"));
        }
        return matchesRelease(selection, data);
    }

    private static boolean matchesRelease(FirmwareSelection selection, JSONObject data) {
        return selection.board().equals(data.optString("board")) && selection.source().equals(data.optString("source")) &&
                selection.role().equals(data.optString("role")) && selection.tag().equals(data.optString("tag")) &&
                selection.name().equals(data.optString("name")) &&
                (selection.sha().isEmpty() || selection.sha().equals(data.optString("sha256")));
    }

    private boolean cachedExportMatches(FirmwareSelection selection) {
        try {
            return library.findCached(false, details -> matchesRelease(selection, details)) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private FirmwareLibrary.Image cacheRepositorySelection(FirmwareSelection selection) throws Exception {
        boolean publishedDigest = selection.sha().matches("[0-9a-f]{64}");
        if (!selection.sha().isEmpty() && !publishedDigest) throw new IOException("Invalid release SHA-256.");
        var downloads = repositories.downloads();
        for (int i = 0; i < downloads.length(); i++) {
            JSONObject file = downloads.getJSONObject(i);
            if (!selection.sha().equals(file.optString("sha256"))) continue;
            try {
                return repositories.loadLocal(file.getString("name"), selection.sha(), file.getLong("size"), library);
            } catch (Exception ignored) {
                break;
            }
        }
        js("window.onFlashDownload()");
        var file = repositories.download(selection.source(), selection.tag(), Long.parseLong(selection.assetId()),
                publishedDigest ? "sha256:" + selection.sha() : "", library);
        publishRepositoryDownloads();
        js("window.onFlashPrepared('Firmware saved to Library.')");
        if (!selection.name().equals(file.getString("name"))) {
            throw new IOException("The selected release file name has changed.");
        }
        return repositories.loadLocal(file.getString("name"), file.getString("sha256"), file.getLong("size"), library);
    }

    private JSONObject findReleaseFile(String board, String role, String source, String tag, String name) throws Exception {
        if (catalogue == null || library == null) throw new IOException("Firmware catalogue is unavailable.");
        var versions = catalogue.versions(board, role, source, library);
        for (int i = 0; i < versions.length(); i++) {
            JSONObject option = versions.getJSONObject(i);
            if (tag.equals(option.getString("tag")) && name.equals(option.getString("name"))) return option;
        }
        throw new IOException("The selected release file is unavailable for this board and role.");
    }

    private void publishCuratedInventory() {
        try { js("window.onCuratedInventory(" + library.inventory() + ")"); }
        catch (Exception error) { failure("firmware.cache", error); }
    }

    private void publishFirmware(JSONObject details, boolean restored) throws JSONException {
        js((details.getBoolean("direct") ? "window.onDownload(" : "window.onCachedExport(") +
                details + ",null," + restored + ")");
        publishCuratedInventory();
    }

    private void beginFlashReview(FlashReview review) {
        UsbDevice device = review.device();
        log.add("flash.usb", "Checking selected device before preparation deviceId=" + device.getDeviceId() +
                " present=" + device.equals(findDevice(device.getDeviceId())) + " permission=" + usb.hasPermission(device));
        if (approved || repeaterBusy || !device.equals(findDevice(device.getDeviceId()))) {
            flashFailed("The selected USB device disconnected or changed.");
            return;
        }
        if (flashDriver(device) == null) {
            flashFailed("The selected USB connection does not support the direct-write protocol for this firmware.");
            return;
        }
        if (usb.hasPermission(device)) {
            prepareFlash(review);
        } else if (pendingPermission >= 0) {
            flashFailed("Finish the pending USB access request, then start Flash again.");
        } else {
            pendingFlashPermission = review;
            js("window.onFlashPrepared('Waiting for USB access…')");
            requestUsbPermission(device, false);
        }
    }

    private void prepareFlash(FlashReview request) {
        UsbDevice device = request.device();
        int id = device.getDeviceId();
        boolean probeOnly = request.probeOnly();
        if (!device.equals(findDevice(id)) || !usb.hasPermission(device)) {
            flashFailed("The selected USB device changed or disconnected.");
            return;
        }
        log.add("flash.prepare", "Preparing image and USB plan deviceId=" + id + " probeOnly=" + probeOnly);
        js("window.onFlashPrepared(" + JSONObject.quote(probeOnly ? "Checking the connected radio layout…" :
                "Checking the connected radio and starting firmware update…") + ")");
        worker.execute(() -> {
            try {
                FirmwareLibrary.Image image = request.image();
                JSONObject plan = probeOnly ? library.layoutDetails().put("tag", "Layout probe") : image.details();
                String targetBoard = plan.optString("board", DIRECT_UPDATE_BOARD);
                String tag = plan.getString("tag");
                plan.put("board", targetBoard).put("usbVendorId", device.getVendorId())
                        .put("usbProductId", device.getProductId());

                var inspected = inspection;
                var observed = inspected != null && device.equals(inspected.device()) ? inspected.identity() : null;
                String reportedBoard = boardOf(device, observed);
                if (!probeOnly && !reportedBoard.isEmpty() && !targetBoard.equals(reportedBoard)) {
                    throw new IOException("Connected device is " + reportedBoard + ", but the selected firmware is for " +
                            targetBoard + ". Select matching firmware before flashing.");
                }

                Runnable start = () -> {
                    if (!device.equals(findDevice(id)) || !usb.hasPermission(device)) {
                        js("window.onComplete(false,'The attached device changed.')");
                        return;
                    }
                    flashRuntimeOriginal = device;
                    flashRuntimeDevices = Set.copyOf(usb.getDeviceList().values());
                    approvedDevice = device;
                    staged = image;
                    approved = true;
                    log.add("flash.algorithm", "Native checks passed; invoking ESP32 serial flasher board=" +
                            targetBoard + " tag=" + tag);
                    log.add("plan", (probeOnly ? "User confirmed probe" : "Flash started") +
                            " deviceId=" + id + " tag=" + tag + " address=0x10000 sha256=" + plan.optString("sha256"));
                    js("window.startFlash(" + id + "," + plan + "," + probeOnly + ")");
                };
                if (!probeOnly) {
                    view.post(start);
                } else {
                    view.post(() -> new AlertDialog.Builder(this)
                            .setTitle("Check flash layout")
                            .setMessage("Selected board: " + targetBoard + ".\n\n" +
                                    "The radio will restart into its bootloader for the chip and flash layout check, then restart again.")
                            .setNegativeButton("Cancel", (dialog, button) -> js("window.onComplete(false,'Layout check canceled.')"))
                            .setOnCancelListener(dialog -> js("window.onComplete(false,'Layout check canceled.')"))
                            .setPositiveButton("Check layout", (dialog, button) -> start.run())
                            .show());
                }
            } catch (Exception error) {
                failure("plan", error);
                if (probeOnly) js("window.onComplete(false," + JSONObject.quote(error.getMessage()) + ")");
                else flashFailed(error.getMessage());
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
        if (request == PICK_LOCAL_FIRMWARE) {
            String board = localBoard;
            localBoard = null;
            if (board != null && result == RESULT_OK && data != null && data.getData() != null) {
                worker.execute(() -> readLocalFirmware(board, data.getData(), true));
            }
            return;
        }
        if (request == PICK_LIBRARY_FIRMWARE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                worker.execute(() -> importLibraryFirmware(data.getData()));
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

    private void writeDfuTransfer(DfuTransfer transfer, UsbDevice device) {
        var initial = Set.copyOf(usb.getDeviceList().values());
        try {
            long driveCount = usb.getDeviceList().values().stream().filter(MainActivity::isMassStorage).count();
            log.add("dfu.install", "Write preconditions driveCount=" + driveCount + " selectedDevicePresent=" +
                    device.equals(findDevice(device.getDeviceId())) + " permission=" + usb.hasPermission(device));
            if (driveCount != 1 || !device.equals(findDevice(device.getDeviceId())) || !usb.hasPermission(device)) {
                throw new IOException("Connect only the radio's UF2 drive and allow its USB access before installing.");
            }
            String board = transfer.selection().board();
            byte[] image = transfer.image().bytes();
            Uf2Volume.Plan plan;
            BootloaderInfo info;
            try (var storage = UsbMassStorage.open(usb, device, log)) {
                plan = Uf2Volume.plan(storage, image.length, log);
                info = BootloaderInfo.parse(plan.info());
                log.add("dfu.bootloader", "Parsed INFO_UF2 boardId=" + info.boardId() + " model=" + info.model() +
                        " version=" + info.version() + " status=" + info.status() + " plannedSectors=" + plan.sectorCount());
                int appStart = MeshCoreCatalogue.uf2Start(board);
                info.requireApplicationCompatibility(board, appStart);
                if (info.softDevice().isEmpty()) {
                    log.add("dfu.bootloader", "SoftDevice not reported; retaining the curated application layout.");
                }
                JSONObject details = transfer.image().details();
                String name = details.getString("name");
                if (!details.optString("board").equals(board) || !MeshCoreCatalogue.safeName(name) ||
                        !name.toLowerCase(Locale.ROOT).endsWith(".uf2")) {
                    throw new IOException("The verified firmware does not have a safe UF2 filename.");
                }
                FirmwareLibrary.validateUf2(image, appStart);
                log.add("dfu.install", "Image checks passed board=" + board + " name=" + name +
                        " bytes=" + image.length + " start=0x" + Integer.toHexString(appStart));
                journal.advance(FlashJournal.Stage.PREPARED, null);
                long[] sectors = plan.sectors();
                int current = 0;
                journal.advance(FlashJournal.Stage.WRITING, null);
                while (current < sectors.length) {
                    int count = 1;
                    if (current < sectors.length - 1) {
                        while (count < 16 && current + count < sectors.length - 1 &&
                                sectors[current + count] == sectors[current] + count) count++;
                    }
                    boolean finalBlock = current == sectors.length - 1;
                    log.add("dfu.install", "Writing destination block index=" + current + " lba=" + sectors[current] +
                            " sectors=" + count + " imageOffset=" + (current * 512) + " final=" + finalBlock);
                    storage.writeBlocks(sectors[current], image, current * 512, count * 512, finalBlock);
                    current += count;
                    if (current == sectors.length || current % 256 == 0) {
                        int percent = current * 100 / sectors.length;
                        log.add("dfu.install", "UF2 sectors written=" + current + "/" + sectors.length + " percent=" + percent);
                        js("window.onDfuTransfer(" + JSONObject.quote("Writing verified UF2 directly over USB… " + percent + "%") + ",false)");
                    }
                }
            }
            journal.advance(FlashJournal.Stage.DELIVERED, null);
            pendingDfu = null;
            dfuWriting = false;
            log.add("dfu.install", "Sent UF2 directly over USB board=" + board + " bootloader=" + info.boardId() +
                    " size=" + image.length + " sha256=" + transfer.image().details().getString("sha256"));
            log.endUsbSession(true, "UF2 blocks delivered to verified bootloader; await device install/reboot");
            js("window.onDfuTransfer('UF2 sent to the matched bootloader.',true,true,true)");
            var details = new JSONObject(transfer.image().details().toString())
                    .put("tag", transfer.selection().tag()).put("role", transfer.selection().role());
            view.post(() -> startRuntime(device, initial, details));
        } catch (Exception error) {
            pendingDfu = null;
            dfuWriting = false;
            failure("dfu.install", error);
            log.endUsbSession(false, error.getMessage());
            publishRecovery();
            js("window.onDfuTransfer(" + JSONObject.quote(error.getMessage()) + ",true,false)");
        }
    }

    private void readLocalFirmware(String board, android.net.Uri uri, boolean picked) {
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
            if (board.equals(DIRECT_UPDATE_BOARD) && name.toLowerCase(Locale.ROOT).endsWith(".bin")) {
                try {
                    var approvedImage = library.findCached(true, entry ->
                            sha.equals(entry.optString("sha256")) && board.equals(entry.optString("board")));
                    if (approvedImage != null) {
                        details.put("route", "remote")
                                .put("tag", approvedImage.details().optString("tag"))
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
            if (picked) rememberLocalFirmware(board, uri);
            js("window.onLocalFirmware(" + details + ",null," + picked + ")");
        } catch (Exception error) {
            failure("firmware.local", error);
            js("window.onLocalFirmware(null," + JSONObject.quote(error.getMessage()) + "," + picked + ")");
        }
    }

    private void importLibraryFirmware(android.net.Uri uri) {
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
            var details = repositories.importLocalFirmware(name, bytes);
            log.add("firmware.library", "Imported " + name + " size=" + bytes.length +
                    " sha256=" + details.getString("sha256"));
            publishRepositoryDownloads();
            js("window.onAssetStatus('Firmware added to Library.')");
        } catch (Exception error) {
            failure("firmware.library", error);
            js("window.onAssetStatus(" + JSONObject.quote(error.getMessage()) + ")");
        }
    }

    private void rememberLocalFirmware(String board, android.net.Uri uri) {
        var preferences = getSharedPreferences("local-firmware", MODE_PRIVATE);
        String previous = preferences.getString("uri", "");
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            preferences.edit().putString("uri", uri.toString()).putString("board", board).apply();
            if (!previous.isEmpty() && !previous.equals(uri.toString())) {
                getContentResolver().releasePersistableUriPermission(android.net.Uri.parse(previous),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (SecurityException error) {
            log.add("firmware.local", "Document provider declined a persistent grant.");
        }
    }

    private void publishRepositoryDownloads() {
        js("window.onRepositoryInventory(" + repositories.downloads() + ")");
    }

    private void publishRepositories() throws JSONException {
        var links = repositories.links();
        js("window.onRepositories(" + links + ")");
        refreshRepositoryMaturity(links, false);
    }

    private void refreshRepositoryMaturity(JSONArray links, boolean refresh) {
        if (library == null || repositoryMetadataWorker.isShutdown()) return;
        repositoryMetadataWorker.execute(() -> {
            for (int i = 0; i < links.length(); i++) {
                var entry = links.optJSONObject(i);
                if (entry == null || entry.optBoolean("bootloader")) continue;
                String url = entry.optString("url");
                try {
                    boolean alpha = repositories.alphaTopic(ReleaseRepositories.parse(url), library, refresh);
                    js("window.onRepositoryMaturity(" + JSONObject.quote(url) + "," + alpha + ")");
                } catch (Exception error) {
                    log.add("repositories", "Repository maturity refresh failed: " + error.getClass().getSimpleName());
                }
            }
        });
    }

    private void inspectBootloader(UsbDevice device) throws IOException {
        long drives = usb.getDeviceList().values().stream().filter(MainActivity::isMassStorage).count();
        if (drives != 1) throw new IOException("Connect only the radio’s UF2 drive before reading bootloader information.");
        try (var storage = UsbMassStorage.open(usb, device, log)) {
            var info = BootloaderInfo.parse(Uf2Volume.readInfo(storage, log));
            if (!device.equals(findDevice(device.getDeviceId()))) throw new IOException("The UF2 drive disconnected.");
            bootloaderInspection = new BootloaderInspection(device, info);
            log.add("bootloader", "INFO_UF2 id=" + info.boardId() + " version=" + info.version() +
                    " softDevice=" + info.softDevice() + " otafix=" + info.status());
        }
    }

    @Override protected void onDestroy() {
        log.add("app", "Activity destroyed");
        closePort("activity closed");
        unregisterReceiver(receiver);
        worker.shutdown();
        repositoryMetadataWorker.shutdownNow();
        super.onDestroy();
    }

    private final class Bridge {
        @JavascriptInterface public float systemTopInset() { return systemTopInset; }

        @JavascriptInterface public void ready() {
            log.add("app", "Local UI ready");
            publishRecovery();
            try (var input = getAssets().open("meshcore-radio-presets.json")) {
                js("window.onRadioPresets(" + new JSONArray(new String(input.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)) + ")");
            } catch (IOException | JSONException error) {
                log.add("repeater.presets", "Bundled presets unavailable");
            }
            if (catalogue != null) js("window.onBoards(" + catalogue.boards() + ")");
            publishDevice(null);
            try { publishRepositories(); } catch (JSONException error) { failure("repositories", error); }
            if (library != null) worker.execute(() -> {
                try {
                    var cached = library.load();
                    js("window.onDownload(" + cached.details() + ",null,true)");
                } catch (FileNotFoundException ordinaryFirstRun) {
                    log.add("cache", "No downloaded image");
                } catch (Exception error) { failure("cache", error); }
                try {
                    js("window.onCachedExport(" + library.loadExport().details() + ",null,true)");
                } catch (FileNotFoundException ordinaryFirstRun) {
                    log.add("cache", "No exportable file cached");
                } catch (Exception error) { failure("cache.export", error); }
                publishCuratedInventory();
                publishRepositoryDownloads();
                var local = getSharedPreferences("local-firmware", MODE_PRIVATE);
                String uri = local.getString("uri", "");
                if (!uri.isEmpty()) readLocalFirmware(local.getString("board", ""), android.net.Uri.parse(uri), false);
            });
        }
        @JavascriptInterface public void scan() { runOnUiThread(() -> scanUsb(++usbScan, 0)); }
        @JavascriptInterface public void connectUsb(int id) { runOnUiThread(() -> MainActivity.this.connectUsb(id)); }
        @JavascriptInterface public void readRepeater(int id) { runOnUiThread(() -> beginReadRepeater(id)); }
        @JavascriptInterface public void revealRadioKey(int id) { runOnUiThread(() -> showRadioPrivateKey(id)); }
        @JavascriptInterface public void saveRepeater(int id, String values) {
            runOnUiThread(() -> confirmRepeater(id, values));
        }
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
        @JavascriptInterface public void flash(String selection) {
            runOnUiThread(() -> beginFlashRequest(selection));
        }

        @JavascriptInterface public void probeLayout(int id) {
            runOnUiThread(() -> {
                UsbDevice device = findDevice(id);
                if (device == null) { flashFailed("The selected USB device is unavailable."); return; }
                beginFlashReview(new FlashReview(device, true, null));
            });
        }
        @JavascriptInterface public void download(String board, String role, String source, String tag, String filename) {
            worker.execute(() -> {
                try {
                    var selected = findReleaseFile(board, role, source, tag, filename);
                    log.add("download", "Selected board=" + board + " role=" + role + " source=" + source + " tag=" + tag);
                    publishFirmware(library.fetch(source, board, role, selected), false);
                } catch (Exception error) {
                    failure("download", error);
                    js("window.onDownload(null," + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }
        @JavascriptInterface public void exportCachedFirmware(String sha, boolean direct) {
            worker.execute(() -> {
                try {
                    var image = library.findCached(direct, entry -> sha.equals(entry.optString("sha256")));
                    if (image == null) {
                        throw new IOException("This firmware file is no longer in the library.");
                    }
                    showSaveFirmware(image);
                } catch (Exception error) {
                    js("window.onAssetStatus(" + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }
        @JavascriptInterface public void removeCuratedFirmware(String key) {
            worker.execute(() -> {
                try {
                    library.removeCached(key);
                    publishCuratedInventory();
                    js("window.onAssetStatus('Offline firmware removed.')");
                } catch (Exception error) {
                    failure("firmware.cache", error);
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
        @JavascriptInterface public void pickLibraryFirmware() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_LIBRARY_FIRMWARE);
                } catch (ActivityNotFoundException error) {
                    failure("firmware.library", error);
                    js("window.onAssetStatus('Android Files is unavailable.')");
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
        @JavascriptInterface public void refreshRepositoryMaturity() {
            try { MainActivity.this.refreshRepositoryMaturity(repositories.links(), true); }
            catch (JSONException error) { failure("repositories", error); }
        }

        @JavascriptInterface public void repositoryAssets(int request, String url, boolean refresh) {
            worker.execute(() -> {
                try {
                    if (library == null) throw new IOException("Firmware library unavailable.");
                    var assets = repositories.assets(url, refresh, library);
                    log.add("repositories", "Firmware assets=" + assets.getJSONArray("files").length() +
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
                    log.add("repositories", "Downloaded " + image.getString("name"));
                    publishRepositoryDownloads();
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
                    js("window.onAssetStatus(" + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface public void removeRepositoryAsset(int request, String sha) {
            worker.execute(() -> {
                try {
                    repositories.removeLocal(sha);
                    publishRepositoryDownloads();
                    js("window.onRepositoryRemoval(" + request + ",null)");
                } catch (Exception error) {
                    failure("repositories.remove", error);
                    js("window.onRepositoryRemoval(" + request + "," + JSONObject.quote(error.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface public void installDfu(String selection) {
            runOnUiThread(() -> beginDfuTransfer(selection));
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
                if (attachedCheck && (!checked.info().matchesBoard(board) ||
                        checked.info().status().equals("installed") || !checked.info().matchesFix(filename))) return;
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
        @JavascriptInterface public void flashMilestone(String milestone) {
            if (!approved || staged == null) return;
            FlashJournal.Stage stage = switch (milestone) {
                case "prepared" -> FlashJournal.Stage.PREPARED;
                case "writing" -> FlashJournal.Stage.WRITING;
                case "delivered" -> FlashJournal.Stage.DELIVERED;
                default -> null;
            };
            if (stage != null) journal.advance(stage, null);
        }
        @JavascriptInterface public void dismissRecovery() {
            runOnUiThread(() -> {
                if (approved || pendingDfu != null || runtimeCheck != null) return;
                journal.clear();
                publishRecovery();
            });
        }
        @JavascriptInterface public boolean finished(boolean success, String reason) {
            var image = staged;
            var original = flashRuntimeOriginal;
            var initial = flashRuntimeDevices;
            flashRuntimeOriginal = null;
            flashRuntimeDevices = Set.of();
            log.add("job", "Completed success=" + success + " " + reason);
            log.endUsbSession(success, reason);
            closePort("job ended");
            staged = null;
            approved = false;
            approvedDevice = null;
            if (success && image != null && original != null) {
                view.post(() -> startRuntime(original, initial, image.details()));
                return true;
            }
            if (image != null) publishRecovery();
            return false;
        }
        @JavascriptInterface public void diagnostic(String stage, String message) { log.add(stage, message); }
        @JavascriptInterface public String logTail() {
            try { return log.tail(); } catch (IOException error) { failure("log", error); return "Log is unavailable."; }
        }
        @JavascriptInterface public void export() { runOnUiThread(() -> exportLog()); }
    }
}
