# AGENTS.md — MeshForge Field Flasher for Android

**Author / product identity** WarDaddy 🪖  
**Product** A native Android app for managing and flashing firmware to authorized LoRa radios over USB.  
**Primary test hardware** Redmi Note 14 Pro as the USB host, Elecrow ThinkNode M5 as the first USB target.  
**Repository baseline** Standalone Android app. Radio firmware is downloaded at runtime and maintained in its own upstream repositories.

## Mission

Build an offline-capable Android Field Flasher. From the phone, find the image for a specific radio and firmware role, download or select a complete image, connect the target by USB, confirm the update, program it, verify it, and report the outcome. The phone handles USB host, internet access, firmware storage, the interface, and diagnostics. Keep normal MeshCore behavior and target configuration intact where the target's update procedure supports it.

The stock ThinkNode M5 and XIAO ESP32-S3 Wio may be USB targets. Their stock ports are not required to be hosts. Wi-Fi or mobile data can fetch firmware. Existing USB bootloaders are the programming path. LoRa firmware transfer and a second mesh stack are outside this project.

## Repository and delivery

- Keep the Android build and manifest at the repository root, with sources in `src/`, resources in `res/`, assets in `assets/`, and tests in `tests/`. Keep board metadata and compatibility checks in the app. Read upstream documentation and releases separately.
- Produce a reproducible Android debug APK and concise build, install, usage, recovery and diagnostics documentation. Pin tool and library versions where possible and preserve third-party licenses. Test on the connected Android phone when the owner makes it available.
- Ask before erasing storage, reflashing a physical device, changing its configuration, or other irreversible hardware actions unless already explicitly authorized for that target. A phone installation permission does not authorize writing a target. Use the M5 as the initial spare target with the owner's approval. Protect private device data.
- No pushes or published builds without permission. Keep firmware backups, log exports, generated APKs and signing material out of Git. Never fabricate results. Record the exact device, build, image digest and verification evidence.

## Remote firmware and offline library

- Use the official MeshCore release source and curated mappings from the official web flasher. Offer SaveMeshcore and MeshCore-Solo through separately verified board, role, and format mappings. Keep manual board selection available when USB identification is ambiguous. Select exact board, role, channel and version. Use immutable asset identifiers and expected SHA-256 when available. Unknown hardware or contradictory evidence requires explicit selection or rejection. A generic bridge VID/PID and chip family cannot prove the board identity.
- Use authenticated HTTPS with system trust roots, allowlisted release and redirect origins, bounded response sizes, timeouts and explicit errors. Do not execute downloaded code or installers. Keep transport verification, verified content hash and publisher signatures distinct. Remote sources are not automatically eligible for unattended flashing.
- Stage each image completely before writing. Validate format, size, layout, digest and release metadata. Complete cache entries atomically, use bounded storage, handle interruption, and keep verified images available offline. Preserve app privacy and avoid exposing firmware backups to unrelated apps.
- Treat ESP app-only, merged and manifested multi-segment images distinctly. The first M5 build supports vetted app-only updates. Full installations, role changes, downgrades, and recovery require explicit separate support and confirmation.

## USB programming

- Use Android's USB Host API and reuse reviewed ESP flashing/serial libraries with pinned revisions. Exercise attachment, permission, connection, time bounds, disconnect, re-enumeration and verification as separate states. Serialize jobs and recheck the selected attachment immediately before writes.
- Support the M5 CH340 path first, including actual ESP32-S3 chip, flash size, security state and app partition checks. Safe updates write only the verified app region. Preserve bootloader, partition tables, identity and settings. Respect secure boot and flash encryption. Log transfer verification and runtime verification separately.
- Add the T-Deck and XIAO ESP32-S3 Wio only after board-specific tests. Later implement and verify USB UF2 for applicable nRF52840 models, including Wio Tracker L1 Pro and T-Echo Lite Kit where their exact bootloaders support it. Some targets need a manual transition to bootloader.
- Use the official flasher’s board-specific OTAFix guidance and read the live bootloader version where available. A verified installed fix skips that step. Preserve unknown status when the reported version or target match is inconclusive.
- Default to identify and explicit confirmation. A future plug-and-update policy requires an exact approved target, source, image, role, verified compatible layout and a bounded authorization. Android USB permission is separate from approval to write.

## On-phone diagnostics

Detailed diagnostics must work with the phone disconnected from the development computer. Save timestamped logs privately across restarts, record app and target versions, show the latest events in the app, and export the complete retained log using Android's document picker. Bound the log size and retain recent failed sessions.

Log discovery and interface/endpoint descriptors, phone USB feature status, attach/detach, permission checks, driver selection, serial open/settings, control-line transitions, bootloader actions, command and response metadata, serial byte counts, elapsed time, retries and exceptions, image and plan validation, download results, write/verify results, and reset outcomes. Never log credentials, private keys, firmware payload bytes, or private backup contents. Report any overflow or retention limit.

## Validation

1. Build the APK using installed Android SDK tools, install via ADB, launch it, and verify on-device persistence and export of diagnostics.
2. Check USB host operation with one real target, starting with discovery and permission, then read-only chip and flash-layout checks.
3. On the authorized spare, verify an exact app-only update, checksum, reboot, and configuration retention. Exercise disconnection and errors before permitting unattended operations.
4. Verify release lookup, interrupted download, wrong digest and layout rejection, offline reuse, and log export without ADB. Record hardware validation and release evidence with the relevant change.

Primary references: [Android USB host](https://developer.android.com/develop/connectivity/usb/host), [Espressif esptool-js](https://github.com/espressif/esptool-js), [MeshCore release assets](https://github.com/meshcore-dev/MeshCore/releases), [official web flasher catalogue](https://github.com/meshcore-dev/flasher.meshcore.io).
