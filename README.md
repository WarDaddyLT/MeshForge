# MeshForge for Android

Choose a board and browse compatible firmware from MeshCore Flasher, SaveMeshcore, and MeshCore-Solo. The app can program approved ThinkNode M5 app images over its WCH USB serial bridge and save verified firmware for other supported boards. Use an Android 13 or newer phone with USB host support. This repository contains the Android app. Radio firmware is obtained from external releases and verified on the phone.

[Download the latest Android APK](https://github.com/WarDaddyLT/MeshForge/releases/latest).

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

**Flash** shows the USB state, selected board, firmware role, repository, and staged image. **Library** shows searchable release rows and role filters. **Repos** opens the repository list, and **Settings** also includes diagnostics. Confirmed flashing and bootloader probes open the progress screen, which reports stages from the live operation.

1. Connect a radio with a USB-C data cable or an OTG adapter. **Scan USB** reports what Android sees. **Probe USB** requests USB access, reads descriptors, and tries the MeshCore device-info query on supported serial targets. Choose the board manually whenever USB identification is ambiguous. Firmware browsing and downloading work without a connected radio.
2. Choose the firmware source, role, and version, then tap **Download image**. The release asset's SHA-256 is checked before caching. The app retains the last direct-write image and last exportable image for offline use.
   For SaveMeshcore, **All published assets** shows the full release by filename, including merged BIN, DFU ZIP, HEX, and checksums. A board choice filters to exact repeater build targets matched by the official catalogue. Assets without an app programming path can be exported after SHA-256 verification.
3. For M5, **Check bootloader layout** checks the bootloader, flash size, partition table and app slot. **Review and flash** confirms the downloaded application image, writes the application slot and compares the flash MD5. Check that the radio boots and that settings and identity are available afterward. SaveMeshcore installs a repeater role. Its settings migration has not been verified.
4. For UF2 firmware, save the verified file and use Android Files to copy it to the radio's UF2 bootloader drive once you have confirmed the exact hardware and SoftDevice. ESP app BIN exports require a separate ESP flasher and the target's known application layout.
5. To check OTAFix, enter UF2 mode, attach the drive and tap **Read INFO_UF2.TXT**. Select that file from the root of the USB drive in Android Files. An explicit OTAFix version suppresses the install step. A recognized stock version and matching board ID enable the official download when the catalogue supplies one. Unknown versions and shared bootloader IDs are shown as such. The RAK 1W profile shows MeshCore’s OTAFix recommendation and official downloads before you read the installed version. The browser downloads the bootloader file separately.
6. **Export log** saves retained diagnostics, including after the app restarts. The app retains four segments of roughly 2 MiB each. Events record timings, responses and protocol sizes. Firmware payloads and private radio data are omitted.

| Board | MeshCore Flasher | SaveMeshcore | MeshCore-Solo |
| --- | --- | --- | --- |
| ThinkNode M5 | BLE companion direct, other matched ESP app BINs export | Repeater direct after matching the published layout | |
| LilyGo T-Deck | ESP app BIN export | Repeater ESP app BIN export | |
| XIAO ESP32-S3 Wio | ESP app BIN export | Repeater ESP app BIN export | |
| Wio Tracker L1 Pro | UF2 export | Repeater UF2 export | OLED companion UF2 export |
| Wio Tracker L1 EINK | UF2 export | | |
| LilyGo T-Echo and T-Echo Lite | UF2 export | Repeater UF2 export | |
| RAK WisMesh 1W Booster (RAK3401 + RAK13302) | UF2 export | Repeater UF2 export | |

For the RAK profile, confirm the RAK13302 radio module and S140 6.1.1 before copying an app UF2. USB identification can recognize the RAK3401 core. It cannot confirm the LoRa module.

The stock T-Echo Lite profile excludes Solo's KeyShield build. The Wio L1 Pro profile excludes the 1W and EINK images. M5 direct updates require USB VID/PID `1a86:7522`, a 4 MiB ESP32-S3, the approved partition table, and a known app0 boot selection. Full installs, unattended updates and direct bootloader writes are unavailable.

Board and OTAFix metadata come from the official flasher's MIT-licensed community entries at revision `45a5257bf792b11a83f4c51c18d4f312addfb677`. Official versions use the live flasher feed. The two forks use their GitHub Releases APIs with exact rules for known board assets, verified against SaveMeshcore `repeater-v1.18.0-savemesh.1` and MeshCore-Solo `v2.3.0`. The nRF bootloader IDs were checked against OTAFix source revision `3a75482e17680169edd86760d122a154b1620c4d`. A locally verified GitHub asset digest establishes download integrity. The app does not verify firmware signatures.

## Repositories and local files

Under **Settings → Repositories**, expand a repository, then expand a release to see its published UF2 files and which ones are downloaded. MeshCore, SaveMeshcore, MeshCore-Solo, and the OTAFix bootloader repository are included. Add a public GitHub repository or release URL to browse other sources. Refresh checks GitHub for newer releases. MeshForge keeps the last list and verified downloads on the phone, so you can reopen the list and save a copy offline. Remove downloads individually to free space. The repository file cache is capped at 64 MiB. A file without a published SHA-256 links to its release on GitHub. Downloading a repository asset does not stage a direct USB write.

**Browse local firmware** opens the Android document picker. MeshForge shows the file size and SHA-256. An M5 application binary can use direct programming when it matches the previously verified cached image. An application UF2 for a supported board can be saved once its family and address range pass validation. Verify the actual hardware variant before copying an application UF2 to its bootloader drive. Other inputs can be inspected.

## Recovery

Keep a known compatible firmware image and the M5's original PC programming path available. After a failed write, save the log and restore the correct image over the existing USB serial bridge. Preserve the target's role and settings when a compatible app-only repair is available.
