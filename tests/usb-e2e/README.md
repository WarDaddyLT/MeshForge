# USB hardware end-to-end checks

These Android instrumentation tests require a real Android phone with USB host/OTG enabled and one MeshCore target connected directly to it. They exercise MeshForge's production USB inspection code and are intentionally read-only: the DFU test validates a real UF2 image, reads the bootloader's USB mass-storage volume and board identity, and calculates the production sector plan, but never calls the USB write method or flashes firmware.

Build and run a serial identity check while the radio is in normal MeshCore mode:

```bash
tests/run-usb-e2e.sh serial "Elecrow ThinkNode M5"
```

Run the DFU check with the radio already in UF2 mode and a trusted, board-matched UF2 file:

```bash
tests/run-usb-e2e.sh dfu "LilyGo T-Echo Lite" /path/to/firmware.uf2
```

Exercise the supported ESP32 USB ROM and flash-layout probe without writing firmware:

```bash
tests/run-usb-e2e.sh esp32-probe "Elecrow ThinkNode M5"
```

For this mode, connect the M5 in normal MeshCore mode and tap **Check layout** on the phone when MeshForge displays its confirmation. The test passes only after the actual ESP32 ROM sync, chip/security checks, flash geometry and partition/OTA layout reads all succeed; the operation resets the device but does not erase or write firmware.

The app's Android USB-permission prompt must be approved on the phone. The DFU check expects `INFO_UF2.TXT` to identify the requested board and validates the UF2 using the app's approved start address. It reports `USB_DFU_READ_ONLY_PASS` only after USB capacity, FAT parsing, board identity, UF2 validation, and available-sector planning succeed; the result includes `writes=0`.

If more than one ADB device is connected, set `ANDROID_SERIAL` to select the phone. The scripts build both APKs, install them, and remove only the uniquely named temporary UF2 copy after the DFU test.