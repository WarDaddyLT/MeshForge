# MeshForge for Android

Choose a board and browse compatible firmware from MeshCore Flasher, SaveMeshcore, and MeshCore-Solo. The app can program approved ThinkNode M5 app images over its WCH USB serial bridge and save verified firmware for other supported boards. Use an Android 13 or newer phone with USB host support. This repository contains the Android app. Radio firmware is obtained from external releases and verified on the phone.

[Download the latest Android APK](https://github.com/WarDaddyLT/MeshForge/releases/latest).

[Visual and UX guide](docs/MeshForge_VISUAL_GUIDE.md)

## AI-Assisted Development Declaration

Details of our AI usage are internal operational matters and are not subject to public disclosure.

<img src="docs/permit.gif" alt="Not to worry. I have a permit." />

## Build

From the repository root:

```sh
node --test app/tests/*.test.mjs
./app/build.sh
```

The script uses Android SDK Platform `android-37.0`, Build Tools `36.0.0`, Android Studio's JDK, Node.js, npm, Git and zip. Set `ANDROID_HOME` and `JAVA_HOME` for other locations. It uses the SDK tools directly and packages a locally signed debug APK in `app/out/`. Only Android build tools are needed.

Libraries are Espressif esptool-js 0.6.0, usb-serial-for-android 3.10.0 at `a8b9ecc7d32ce6df749c44a2b9e8cb208ac30609`, and esbuild 0.25.10 for the JavaScript bundle. The WebView serial bridge pattern follows esp-flash-android revision `c4036ace9d2f5526e3535d1ac8bfb8ac3bafc23d`. The APK includes the MIT and Apache-2.0 licenses. The build generates the serial library's compile-time configuration and omits its AndroidX source annotation.

### GitHub Actions

[Android build](.github/workflows/android.yml) runs the tests and APK build on pushes to `main` and pull requests. To run it manually, open **Actions → Android build → Run workflow**. Download `meshforge-android-debug` from a successful run for the APK and its SHA-256 checksum.

Each CI run signs with its own debug key. Android requires the same signing key for upgrades, so use the APK under **Releases** to update a release installation. The workflow uploads test artifacts only. Release publishing uses the maintainer's signing key.

## Use

MeshForge opens to **Configurator**. The bottom bar also opens **Flash**, **Library**, and **Settings**. The app runs in portrait. Settings shows repositories and diagnostics together. Android Back returns from Flash Progress to Flash and from a bottom-bar destination to Configurator.

### Configure a radio over USB

1. Connect a radio already running MeshCore repeater or room-server firmware, including one you just flashed. On **Configurator**, choose the USB device and tap **Read settings**. Grant Android USB access when asked. The radio must report a supported role and public key over the USB CLI.
2. Edit the supported fields for name, coordinates, owner info, passwords, room access, forwarding, announcements, radio parameters, access control, regions and advanced options. The app bundles the community radio presets for offline use. Device information shows the role, firmware version, clock and public key. Viewing the private key requires opening a protected Android dialog.
3. Tap **Save settings** and confirm. MeshForge rechecks the device and its current settings before writing. It verifies saved settings by readback or, for the write-only admin password, the radio’s acknowledgement. If a save stops early, read settings again to see what was applied. Restart the radio after saving new LoRa parameters.

Configurator field behavior follows [the MeshCore configurator](https://github.com/meshcore-dev/config.meshcore.io/tree/aff42ad8ef2b332f24d644ff6a783f8272a88ff2). Radio preset data comes from [the MeshCore configuration API](https://api.meshcore.nz/api/v1/config).

### Flash firmware

1. Connect a radio with a USB-C data cable or an OTG adapter. In **Flash**, **Find USB devices** reports all attachments Android exposes. **Read device info** requests USB access and tries to identify MeshCore USB companion, repeater, or room-server firmware. A Bluetooth-only companion can expose a USB programming bridge while its firmware stays silent. Choose its exact board manually in that case.
2. On **Flash**, select a firmware repository, role and release, then tap **Flash**. To save firmware for later, tap **Download firmware**. Choose **Local file** to open firmware in Android Files. Choose the board manually whenever USB identification is ambiguous. Firmware browsing and downloading work without a connected radio. The release asset's SHA-256 is checked before caching. The app retains the last direct-write image and last exportable image for offline use.
   For SaveMeshcore, **Show all release files** shows the full release by filename, including merged BIN, DFU ZIP, HEX, and checksums. A board choice filters to exact repeater build targets matched by the official catalogue. Assets without an app programming path can be exported after SHA-256 verification.
3. On supported radios, **Check flash layout** checks the bootloader, flash size, partition table and app slot and reports the result in **Flash**. **Flash** highlights missing inputs and opens **Flash Progress**. Progress loads the selected image from the phone or downloads and verifies it as needed. MeshForge requests Android USB access and asks for confirmation before writing. The confirmation shows the selected board and any different model the radio reports. Progress checks the actual layout before writing and verifies the flash MD5 afterward. Check that the radio boots and that settings and identity are available afterward. SaveMeshcore installs a repeater role. Its settings migration has not been verified.
4. On a board with a supported UF2 application profile, choose a board-matched MeshCore UF2 release and tap **Flash**. Confirm the image, then follow Flash Progress to enter UF2 mode if the radio does not switch automatically. MeshForge checks the bootloader’s board ID before sending the UF2. **UF2 sent** reports delivery. Verify that the radio restarts into MeshCore. Other UF2 files can be exported for the board’s own update procedure. ESP app BIN exports require a separate ESP flasher and the target’s known application layout.
5. **OTAFix** on Flash shows possible packages from MeshCore’s board catalogue and configured OTAFix repositories. Select a package and download it for the board’s bootloader installation procedure. To check the installed version, enter UF2 mode and choose **Check installed OTAFix**, then select the INFO_UF2.TXT file on the radio’s USB drive. A matching bootloader that reports OTAFix shows as installed. Package selection shows as selected.
6. **Export log** under Diagnostics on **Settings** saves retained diagnostics, including after the app restarts. The app retains four segments of roughly 2 MiB each. Events record timings, responses and protocol sizes. Firmware payloads and private radio data are omitted.

| Board | MeshCore Flasher | SaveMeshcore | MeshCore-Solo |
| --- | --- | --- | --- |
| ThinkNode M5 | Bluetooth companion, USB companion, and repeater direct. Other matched ESP app BINs export. | Repeater direct after matching the published layout | |
| LilyGo T-Deck | ESP app BIN export | Repeater ESP app BIN export | |
| XIAO ESP32-S3 Wio | ESP app BIN export | Repeater ESP app BIN export | |
| Wio Tracker L1 Pro | UF2 export | Repeater UF2 export | OLED companion UF2 export |
| Wio Tracker L1 EINK | UF2 export | | |
| LilyGo T-Echo and T-Echo Lite | UF2 export | Repeater UF2 export | |
| RAK WisMesh 1W Booster (RAK3401 + RAK13302) | UF2 export | Repeater UF2 export | |

For the RAK profile, confirm the RAK13302 radio module and S140 6.1.1 before copying an app UF2. USB identification can recognize the RAK3401 core. It cannot confirm the LoRa module.

The stock T-Echo Lite profile excludes Solo's KeyShield build. The Wio L1 Pro profile excludes the 1W and EINK images. Direct updates for the M5 firmware profile require a supported USB serial connection, a 4 MiB ESP32-S3, the approved partition table, and a known app0 boot selection. The flash confirmation identifies a reported role change or an unknown current role. Full installs, unattended updates and direct bootloader writes are unavailable.

The v1.18.0 M5 USB companion and official repeater app files matched the SHA-256 digests published by MeshCore. Both matched the app bytes at `0x10000` in their paired merged images and contained the expected partition table in those merged images. [Companion release](https://github.com/meshcore-dev/MeshCore/releases/tag/companion-v1.18.0) · [Repeater release](https://github.com/meshcore-dev/MeshCore/releases/tag/repeater-v1.18.0)

Board and OTAFix metadata come from the official flasher's MIT-licensed community entries at revision `45a5257bf792b11a83f4c51c18d4f312addfb677`. Official versions use the live flasher feed. The two forks use their GitHub Releases APIs with exact rules for known board assets, verified against SaveMeshcore `repeater-v1.18.0-savemesh.1` and MeshCore-Solo `v2.3.0`. The nRF bootloader IDs were checked against OTAFix source revision `3a75482e17680169edd86760d122a154b1620c4d`. A locally verified GitHub asset digest establishes download integrity. The app does not verify firmware signatures.

## Repositories and local files

Manage public GitHub repository and release URLs on **Settings**. MeshCore, SaveMeshcore, MeshCore-Solo, [Meshcore-Solo-Zygis](https://github.com/WarDaddyLT/Meshcore-Solo-Zygis), and an OTAFix repository are included initially. Firmware sources appear on **Flash**. Use the **OTAFix** category for repositories that publish fixes. Library can download a repository file with no published checksum and labels it accordingly. Direct flashing requires a verified image for the selected board.

**Library** groups release files by repository and version. It marks saved files as available offline and shows imported firmware under **Local files**. It shows active downloads and lets you export files or remove individual repository downloads. The repository file cache is capped at 64 MiB. The curated firmware cache keeps the latest direct-write and exportable image.

Choose local firmware on **Flash**. MeshForge shows the file size and SHA-256. An M5 application binary can use direct programming when it matches the previously verified cached image. An application UF2 from a local file or a repository with a published checksum can be flashed using a supported board profile once its family and address range pass validation. Verify the actual hardware variant before copying a UF2 to its bootloader drive.


## Recovery

Keep a known compatible firmware image and the M5's original PC programming path available. After a failed write, save the log and restore the correct image over the existing USB serial bridge. Preserve the target's role and settings when a compatible app-only repair is available.
