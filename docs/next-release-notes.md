# Next MeshForge beta

Draft notes for the current feature pull requests. The release version and APK will be set when the candidate is approved.

## Flashing and recovery

- Read the connected UF2 bootloader’s board ID, model, version, and reported SoftDevice over USB. [PR #60](https://github.com/WarDaddyLT/MeshForge/pull/60)
- Check whether the radio reconnects and reports the expected firmware. Flash Progress distinguishes transfer results from a confirmed, mismatched, or unconfirmed startup. [PR #61](https://github.com/WarDaddyLT/MeshForge/pull/61)
- Show the last flash stage after a failure or app restart. The recovery message identifies whether writing started, gives the applicable ESP or UF2 recovery step, and includes the selected image and checksum when known. [PR #62](https://github.com/WarDaddyLT/MeshForge/pull/62)
- Compare the bootloader’s reported SoftDevice with the selected nRF52 UF2 application layout before writing. [PR #65](https://github.com/WarDaddyLT/MeshForge/pull/65)
- Derive app-only write locations from paired official ESP32 or ESP32-S3 release images. Match the target’s chip, security state, flash capacity, live partition table and boot-slot state before writing. [PR #66](https://github.com/WarDaddyLT/MeshForge/pull/66)

## Offline Library

- Keep several verified curated images in a bounded cache. Existing app and export entries migrate on first use. Remove individual entries from Library to free space. [PR #63](https://github.com/WarDaddyLT/MeshForge/pull/63)
- Choose **Saved firmware** in Flash to use an exact compatible file without a release lookup. [PR #64](https://github.com/WarDaddyLT/MeshForge/pull/64)

## Test evidence

- The prior development builds have owner-reported successful writes on ThinkNode M5 and stock T-Echo Lite. The Wio Tracker L1 E-Ink exposed a UF2 drive on macOS and reported S140 7.3.0.
- The new ESP layout parser validated both SHA-256-checked files from the official XIAO ESP32-S3 Wio companion v1.18.0 release. It identified ESP32-S3, app offset `0x10000`, app slot length `0x330000`, and an 8 MiB flash layout. [Official release](https://github.com/meshcore-dev/MeshCore/releases/tag/companion-v1.18.0)
- Run the WebView and JVM suites, build the release candidate, and install that exact APK on Android. Before publishing, collect representative USB write, reboot, and settings-retention evidence for each programming path. The emulator covers app workflows. Physical USB and Wio results are tracked separately. [ESP #2](https://github.com/WarDaddyLT/MeshForge/issues/2) · [UF2 #3](https://github.com/WarDaddyLT/MeshForge/issues/3) · [Wio #35](https://github.com/WarDaddyLT/MeshForge/issues/35)
