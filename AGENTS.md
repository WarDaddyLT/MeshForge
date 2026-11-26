# AGENTS.md — MeshForge for Android

**Product and maintainer** MeshForge by WarDaddy 🪖

MeshForge is an offline-capable Android USB field flasher and Configurator for MeshCore radios. The phone handles USB host, downloads, verification, diagnostics, and firmware storage. Firmware comes from its own release repositories. USB bootloaders are the current programming path. LoRa firmware transfer and a second mesh stack are outside scope.

## Current baseline

- The published APK is `v0.1.16`. The current `main` branch is a limited beta candidate. For release decisions, verify the latest tag, open issues, exact APK, signing identity, and on-device results.
- The Redmi Note 14 Pro is the primary Android host. The owner reported successful ThinkNode M5 app-only and stock T‑Echo Lite UF2 updates on earlier development builds. The Wio Tracker L1 E‑Ink enumerated as UF2 on macOS. Full Wio write and reboot validation is pending.
- The ESP writer implements vetted M5 application updates. Implemented UF2 application profiles cover the stock T‑Echo and T‑Echo Lite, Wio Tracker L1 Pro and E‑Ink, and RAK WisMesh 1W Booster. Keep implemented paths and individually verified hardware results distinct in user-facing documentation.
- The main destinations are **Configurator**, **Flash**, **Library**, and **Settings**. **Flash Progress** is a child of Flash. Configurator is the launch screen and works with an existing repeater or room server. Prioritize the existing form for radio parameters, admin passwords, and node information. Keep map and coordinate-specific UX outside the current feature scope.
- Prioritize runtime verification after flashing, reliable ESP32 serial and nRF52 UF2 paths, direct bootloader inspection, offline firmware selection, and focused physical validation of Configurator and diagnostics. Use available radios as representative test hardware and track remaining work in the GitHub backlog.

## Repository and delivery

- Keep the build and manifest at the repository root, with sources in `src/`, resources in `res/`, WebView assets in `assets/`, and tests in `tests/`. Build outputs and local signing material belong in ignored `out/`. Keep board metadata and compatibility checks in the app.
- Run `node --test tests/*.test.mjs` and `./build.sh` from the root. Build the Android instrumentation APK using `./tests/build-usb-e2e.sh` for hardware work. Keep tool and library versions pinned, preserve third-party licenses, and update usage and recovery documentation when behavior changes.
- Use one focused issue, commit, branch, and PR per deliverable when publishing changes. Target the maintainer’s own repository and keep upstream projects as references and firmware sources. Push or publish only with authorization. For a release, increment the Android version code, use the established release signing key, and smoke-test the exact published candidate on hardware.
- Ask before erasing, reflashing, changing configuration, or performing other irreversible hardware actions unless the owner already explicitly authorized that target and action. Phone installation permission is separate from radio write permission. Keep backups, exported logs, generated APKs, private radio data, and signing material out of Git.

## Firmware selection and storage

- Use the official MeshCore release source and catalogue, plus vetted SaveMeshcore and MeshCore-Solo mappings and configured public GitHub sources. Always preserve explicit manual board selection. A bridge VID/PID or chip family alone is insufficient to identify the exact board variant.
- Stage the exact selected file using its immutable asset ID and published SHA-256 where available. Use HTTPS with system trust roots, allowlisted release and redirect origins, timeouts, bounded responses, and explicit errors. Treat transport integrity, a published hash, and publisher signatures as separate properties. Never execute downloaded code or installers.
- Check format, size, application layout, and digest before writing. Preserve atomic metadata and file writes, bound storage, handle interruptions, and reuse verified offline firmware. The repository download budget is 64 MiB. The curated cache retains multiple validated releases within a separate 32 MiB limit.
- Use release tags for individual alpha, beta, and release-candidate labels. Use GitHub’s prerelease flag for less specific releases. A repository’s `firmware-alpha` topic supplies an Alpha default even before the first release. Keep this metadata-driven for all repositories.
- Distinguish ESP app-only updates from full merged or manifested images, and UF2 applications from serial DFU ZIPs and bootloader packages. Keep the user’s file choice through staging. Report actual unsupported write paths in Flash Progress. Retain explicit confirmation before physical writes.

## USB programming and Configurator

- Use Android USB Host and the pinned serial and ESP libraries. Treat discovery, permission, layout check, write, verification, reset, and reconnect as separate phases. Serialize operations and recheck device attachment before writing.
- Generalize the ESP32 serial writer around the detected chip, flash size, security state, partition layout, and boot selection. Verify the exact board and image before writing the approved application range, then verify written MD5. Respect secure boot and flash encryption. Test on available representative hardware and record which chip variants and layouts passed.
- Generalize the nRF52 UF2 writer around its detected bootloader and verified image layout. Check the UF2 family and address range, bootloader identity, and available SoftDevice metadata. Report serial-only DFU separately and handle manual entry where needed. A shared bootloader ID may span radio or display variants. Record transfer delivery, runtime verification, and the hardware actually tested.
- Use the official board-specific OTAFix guidance. Show package availability, selection, and installation as distinct states. The current workflow uses `INFO_UF2.TXT` and package export. Keep the reported status unknown when identity or version is ambiguous.
- In Configurator, use firmware capability checks and send only changed settings. Recheck the attached radio and verify writes by readback or acknowledgement as appropriate. Preserve credential privacy and the ability to inspect existing settings independently of flashing.

## Diagnostics and validation

- Keep timestamped, bounded USB logs private on the phone across restarts. Save recent failed sessions and provide a complete retained export through Android Files. Log relevant device and interface descriptors, permission, driver and transport events, command metadata, timings, sizes, validation, transfers, reset and reconnect. Exclude credentials, private keys, raw firmware payloads, and private backup data.
- Install and launch the APK on the available Android phone. Verify offline reuse, interrupted downloads, failure recovery, and diagnostic export without ADB. Use the read-only ESP layout and UF2 checks where appropriate.
- Record the actual board and variant, APK commit/version, firmware asset and digest, transfer result, runtime identity, and settings-retention evidence for hardware tests. Distinguish source and fixture tests, reported user successes, read-only hardware checks, and full writes. Keep a known recovery procedure for each supported write profile.

Primary references: [Android USB host](https://developer.android.com/develop/connectivity/usb/host), [Espressif esptool-js](https://github.com/espressif/esptool-js), [MeshCore releases](https://github.com/meshcore-dev/MeshCore/releases), [official flasher catalogue](https://github.com/meshcore-dev/flasher.meshcore.io), and [MeshForge backlog](https://github.com/WarDaddyLT/MeshForge/issues?q=is%3Aissue+is%3Aopen).
