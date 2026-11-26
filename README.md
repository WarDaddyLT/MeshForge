# MeshForge for Android

MeshForge is a USB field flasher and Configurator for MeshCore radios. Use an Android 13 or newer phone with USB host support to find firmware, save it for offline use, program a supported radio, or change settings on an existing repeater or room server. Use a USB data cable or OTG adapter.

**Release status.** The published APK is **v0.1.16**. The capabilities below describe the current `main` branch, which you can build locally. The next build is a limited beta candidate.

[Download a published APK](https://github.com/WarDaddyLT/MeshForge/releases) · [See the open backlog](https://github.com/WarDaddyLT/MeshForge/issues?q=is%3Aissue+is%3Aopen) · [Visual and UX guide](docs/MeshForge_VISUAL_GUIDE.md) · [Next beta notes](docs/next-release-notes.md)

## What MeshForge does

| Screen | Purpose |
| --- | --- |
| **Configurator** | Read and save radio parameters, admin and guest passwords, and node information on a repeater or room server over USB. Available settings depend on the firmware. Community radio presets work offline. |
| **Flash** | Choose the USB target, board, firmware source or local file, role, and release. **Flash** opens progress, stages the selected file from storage or downloads it if needed, then asks for confirmation before writing. |
| **Firmware Library** | Browse releases by repository and version. Import local firmware, download files for offline use, and export or remove saved repository copies. |
| **Settings** | Manage firmware and OTAFix repositories and export retained diagnostic logs through Android Files. |

## USB flashing support on `main`

| Target | Implemented write path | Hardware evidence |
| --- | --- | --- |
| Elecrow ThinkNode M5 | ESP32-S3 app-only update for mapped MeshCore Bluetooth companion, USB companion, and repeater images, plus the mapped SaveMeshcore repeater image. Checks flash layout and verifies written bytes with MD5. | Owner reported a successful M5 app update on an earlier development build. |
| LilyGo T‑Echo Lite | Stock bootloader, application UF2 over USB. | Owner reported a successful UF2 update on an earlier development build. |
| Wio Tracker L1 Pro and L1 E‑Ink | Separate board profiles for application UF2 over USB. | The L1 E‑Ink enumerated as a UF2 drive on macOS. End-to-end Wio flashing and reboot are pending. |
| LilyGo T‑Echo and RAK WisMesh 1W Booster | Application UF2 over USB for the listed profiles. For the RAK profile, confirm the RAK3401 module, RAK13302 radio, and S140 6.1.1 for the selected image. | End-to-end validation pending. |
| Other ESP32 and ESP32‑S3 boards | For supported official releases, derive an app-only write plan from the matching full image and require the live partition table to match. | End-to-end validation pending on additional hardware. |

The M5 writer updates the existing application partition. **Merged BIN** is a full-install format you can save or export. **UF2 sent** means the image reached the matched bootloader. Check that the radio restarts into the expected firmware and its settings are available. Firmware variants are separate targets, including the Wio e‑paper and 1 W variants and the T‑Echo Lite KeyShield build. [Board validation and support matrix](https://github.com/WarDaddyLT/MeshForge/issues/31)

## Typical workflows

- **Configure a radio.** Connect an existing MeshCore repeater or room server and tap **Read settings**. Edit the supported fields and tap **Save settings**. MeshForge sends the changed values and checks readback where possible. For the write-only admin password, it checks the radio’s acknowledgement. Restart the radio after changing LoRa parameters. [Configurator reference](https://github.com/meshcore-dev/config.meshcore.io/tree/aff42ad8ef2b332f24d644ff6a783f8272a88ff2) · [Radio preset data](https://api.meshcore.nz/api/v1/config)
- **Flash firmware.** Select the exact board, role and file. If USB identification is ambiguous, choose the board manually. Grant USB access if prompted. Follow **Flash Progress** for manual bootloader entry when needed. A local M5 BIN can use direct programming when it matches a verified cached image. A local application UF2 or a repository UF2 with a published checksum can use a supported board profile once it passes validation.
- **Check OTAFix.** **Flash** lists available board-matched packages. Enter UF2 mode and tap **Read UF2 bootloader info** to read `INFO_UF2.TXT` directly over USB. The app tracks package selection and reported installation separately. Install an exported fix with the board’s bootloader procedure.
- **Recover or report a problem.** Open **Settings → Diagnostics → Export log**. Logs are retained on the phone across restarts. Include the board, firmware role and version, APK version, and stage that failed. Use a known compatible image and the manufacturer’s recovery procedure if the radio does not restart.

## Firmware sources and offline use

The built-in sources include MeshCore, SaveMeshcore, MeshCore-Solo, Meshcore-Solo-Zygis, MeshCore-Hardened, and an OTAFix repository. Add or remove public GitHub repositories and release URLs in **Settings**. MeshForge uses the official catalogue for board and role mappings and reads GitHub Releases for repository files. [Official MeshCore flasher](https://github.com/meshcore-dev/flasher.meshcore.io) · [Meshcore-Solo-Zygis](https://github.com/WarDaddyLT/Meshcore-Solo-Zygis) · [MeshCore-Hardened](https://github.com/WarDaddyLT/MeshCore-Hardened)

Zygis and Hardened carry the GitHub topic `firmware-alpha`. MeshForge displays **Alpha** on these sources and uses it as the default for unqualified release tags. Explicit alpha, beta, or release-candidate tags label the individual file. GitHub’s prerelease flag marks other prereleases.

The repository download cache is capped at **64 MiB**. The curated firmware cache keeps multiple versions within a separate 32 MiB limit. Remove saved files in the Library to free space. In **Flash**, choose **Saved firmware** to select a compatible offline file without a release lookup. The Library identifies files with no published checksum. Those files can be downloaded for inspection or export. MeshForge checks published digests where available and validates the board profile and write layout before programming. A SHA-256 match establishes download integrity. Publisher-signature verification is outside the current release scope.

## Build and install

From the repository root:

```sh
node --test tests/*.test.mjs
./tests/run-jvm-tests.sh
./build.sh
adb install -r out/meshforge-field-flasher-debug.apk
```

The build uses Android SDK Platform `android-37.0`, Build Tools `36.0.0`, Java 17 or newer, Node.js, npm, Git, and zip. Set `ANDROID_HOME` and `JAVA_HOME` for other locations. A checkout created before the root move can retain its local debug signing identity by copying `app/out/debug.keystore` to `out/debug.keystore` before building. [Android build workflow](.github/workflows/android.yml) · [USB host test harness](tests/usb-e2e/README.md)

GitHub Actions builds a test APK and checksum on pull requests and pushes to `main`. CI artifacts use a fresh debug key. Use the APK under **Releases** for an in-place upgrade of the published app. Releases use the maintainer’s signing key.

The build pins esptool-js 0.6.0, usb-serial-for-android 3.10.0 at `a8b9ecc7d32ce6df749c44a2b9e8cb208ac30609`, and esbuild 0.25.10. The APK bundles third-party license notices, including the reference for the WebView serial bridge.

## Project goals

- Verify that a radio restarts into the expected firmware after writing, with separate transfer and runtime results.
- Make ESP32 serial and nRF52 UF2 flashing work across compatible hardware. Validate each path on radios we have access to and publish the actual results. Check the selected image, bootloader and flash layout before each write. [ESP32](https://github.com/WarDaddyLT/MeshForge/issues/2) · [nRF52](https://github.com/WarDaddyLT/MeshForge/issues/3)
- Make several verified firmware versions available offline and selectable directly from Flash.
- Validate the existing Configurator form and diagnostics export on physical radios and the phone.
- Improve read-only bootloader checks and document tested recovery paths.

## AI-Assisted Development Declaration

Details of our AI usage are internal operational matters and are not subject to public disclosure.

<img src="docs/permit.gif" alt="Not to worry. I have a permit." />
