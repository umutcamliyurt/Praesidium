<div align="center">

<br/>

# An Android physical security tool

<br/>

<img src="banner.png" width="500">

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![API](https://img.shields.io/badge/min%20API-26-informational?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

<br />

</div>

---

## Overview

Praesidium is an Android security tool designed to protect devices from physical and software-level intrusion. Running as a persistent foreground service, it continuously monitors for threat indicators — ADB connections, brute-force unlock attempts, and anomalous process behaviour — and responds with configurable emergency actions including screen lock, device reboot, or full factory wipe.

It is intended for security-conscious users, researchers, and administrators who require automated, policy-driven responses to device compromise scenarios.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation & Setup](#installation--setup)
- [Threat Detection](#threat-detection)
- [Emergency Actions](#emergency-actions)
- [Configuration Reference](#configuration-reference)
- [Known Limitations](#known-limitations)
- [License](#license)

---

## Features

| Capability | Description |
|---|---|
| **ADB Connection Detection** | Detects active ADB sessions via `/proc/net/tcp` inspection |
| **Brute-Force Protection** | Tracks cumulative failed unlock attempts via the Device Admin API |
| **FD Spike Detection** | Monitors `adbd` file descriptor count for anomalous growth |
| **Automated Response** | Per-threat configurable actions: lock, reboot, or wipe |
| **Confirmation Counting** | Reboot requires 5 consecutive confirmations; wipe requires 10 — prevents single-poll false positives |
| **Manual Emergency Controls** | One-tap lock, reboot, and wipe from the main UI |
| **Admin Toggle** | Activate and deactivate Device Administrator and Device Owner status from within the app |
| **Persistence** | Service auto-restarts after device reboot via `BOOT_COMPLETED` |
| **Granular Settings** | Individual toggle and action assignment for each threat type |

---

## Requirements

### Permissions

| Permission | Purpose |
|---|---|
| `RECEIVE_BOOT_COMPLETED` | Restart service after device boot |
| `FOREGROUND_SERVICE` | Persistent background monitoring |
| `BIND_DEVICE_ADMIN` | Device Administrator binding |

### Device Admin

Lock functionality requires the app to be granted **Device Administrator** privileges. The user is prompted to grant this on first launch. Privileges can be revoked at any time using the **Deactivate** button on the main screen.

### Device Owner *(optional)*

Reboot and wipe actions require **Device Owner** status. This must be configured before use:

```bash
adb shell dpm set-device-owner com.nikolas-trey.praesidium/.DeviceAdminReceiver
```

> ⚠️ Device Owner can only be set on a device with no accounts added, or via ADB before setup is complete. See [Installation & Setup](#installation--setup) for the correct sequence.

---

## Installation & Setup

### 1. Build & Install

```bash
# Clone the repository
git clone https://github.com/nikolas-trey/Praesidium.git
cd Praesidium

# Build release APK
./gradlew assembleRelease

# Install to connected device
adb install app/build/outputs/apk/release/app-release.apk
```

### 2. Grant Device Owner *(recommended — do this before adding any accounts)*

To enable reboot and wipe actions, set the app as Device Owner **before** adding any Google or other accounts to the device. The easiest time to do this is immediately after a factory reset, during initial setup:

```bash
# Skip adding a Google account in the setup wizard, then:
adb shell dpm set-device-owner com.nikolas-trey.praesidium/.DeviceAdminReceiver
```

If accounts are already present, remove them all via **Settings → Accounts**, then run the command above.

> ⚠️ Device Owner cannot be set if any accounts exist on the device. The command will throw `IllegalStateException` if this condition is not met.

### 3. Grant Device Administrator

Open the app. If Device Owner was not set via ADB, a system dialog will appear requesting Device Administrator access. Grant it to enable at minimum the lock action.

### 4. Configure Threat Responses

Open **Settings** within the app to:
- Enable or disable individual threat detectors
- Set the failed unlock threshold (minimum: 3)
- Assign an action (lock / reboot / wipe) to each threat type

The service starts automatically on launch and persists across reboots.

### 5. Deactivating

To remove all privileges, tap the **Deactivate** button on the main screen. This clears Device Owner status first, then removes Device Administrator, and stops the background service. A confirmation dialog is shown before any changes are made.

---

## Threat Detection

### ADB Connection — `adb_connected`

**Method:** Parses `/proc/net/tcp` and `/proc/net/tcp6` on every poll cycle.

**Trigger:** An ESTABLISHED connection (`state = 0x01`) on either of the standard ADB ports:

| Port (decimal) | Port (hex) | Protocol |
|---|---|---|
| 5555 | `0x15B3` | ADB over TCP |
| 5037 | `0x13AD` | ADB host daemon |

**Boot loop protection:** The ADB action is capped at **lock** regardless of the configured action. Allowing reboot or wipe for ADB detection would cause an infinite boot loop if a cable remains connected across reboots.

**Default action:** Lock

---

### Brute-Force Unlock — `brute_force`

**Method:** `DeviceAdminReceiver.onPasswordFailed()` fires for every failed unlock attempt. A cumulative counter is persisted in SharedPreferences using synchronous `commit()` writes to ensure data survives an immediately following reboot or wipe.

**Trigger:** Counter reaches or exceeds the configured threshold (default: 5, minimum enforced: 3).

**Boot grace period:** Brute-force detection is suppressed for **5 minutes** after boot. This prevents a counter left over from a previous session from firing an action the moment the service starts.

**Reset behaviour:**
- Automatic reset on successful unlock (`onPasswordSucceeded`) — synchronous write
- Automatic reset before action is executed — synchronous write, ensuring the counter reaches disk before a reboot or wipe can erase it

**Default action:** Lock

---

### ADB FD Spike — `adb_fd_spike`

**Method:** At service start, the `adbd` process's open file descriptor count is sampled multiple times from `/proc/<pid>/fd` to establish a stable baseline. On each poll, the current count is compared to this baseline.

**Trigger:** Current FD count exceeds `baseline + 15`.

**Baseline requirements:** At least 3 out of 5 calibration samples must succeed. If fewer succeed, FD spike detection is disabled for the session to prevent a bad baseline from producing instant false positives.

**Boot grace period:** FD spike detection is suppressed for **2 minutes** after service start. `adbd` opens and closes file descriptors during early initialisation; a low calibration baseline combined with normal post-boot activity would otherwise produce false spikes.

**Default action:** Lock

---

## Emergency Actions

All actions are executed via `DevicePolicyManager` and guarded by capability checks. If the required privilege is not held, the action falls back to lock rather than failing silently.

| Action | Capability Required | Confirmation Count | Behaviour |
|---|---|---|---|
| **Lock** | Device Administrator | 1 | Calls `lockNow()` — immediately locks the screen |
| **Reboot** | Device Owner | 5 | Calls `dpm.reboot()` — performs a clean system reboot |
| **Wipe** | Device Owner | 10 | Calls `wipeData()` with `WIPE_EXTERNAL_STORAGE` and `WIPE_RESET_PROTECTION_DATA` — full factory reset |

> ⚠️ **Wipe is irreversible.** All user data, installed apps, and external storage content will be permanently erased. Manual wipe from the UI requires explicit confirmation. Automated wipe executes when the threat persists for 10 consecutive poll cycles (~10 seconds).

---

## Configuration Reference

All preferences are stored in `SharedPreferences` under the file name `praesidium_prefs`.

### Detection Toggles

| Key | Type | Default | Description |
|---|---|---|---|
| `detect_adb` | `boolean` | `true` | Enable ADB connection detection |
| `detect_brute_force` | `boolean` | `true` | Enable brute-force unlock detection |
| `detect_fd_spike` | `boolean` | `true` | Enable ADB file descriptor spike detection |
| `failed_unlock_threshold` | `int` | `5` | Failed unlocks before brute-force triggers (minimum: 3) |

### Action Assignments

| Key | Type | Default | Valid Values | Notes |
|---|---|---|---|---|
| `action_adb` | `string` | `lock` | `lock`, `reboot`, `wipe` | Reboot/wipe silently demoted to `lock` at runtime |
| `action_brute_force` | `string` | `lock` | `lock`, `reboot`, `wipe` | |
| `action_fd_spike` | `string` | `lock` | `lock`, `reboot`, `wipe` | |
| `default_action` | `string` | `lock` | `lock`, `reboot`, `wipe` | Fallback for unrecognised threat keys |

### Internal State

| Key | Type | Description |
|---|---|---|
| `failed_unlock_cumulative` | `int` | Running count of failed unlock attempts since last reset |
| `response_enabled` | `boolean` | Master switch — disables all automated responses when `false` |

---

## Known Limitations

- **`/proc` access** — Reading `/proc/net/tcp` and `/proc/<pid>/fd` may be restricted on hardened or custom ROMs. Detection will silently fail (return no threat) if access is denied.
- **Device Owner requirement** — Reboot and wipe require Device Owner status, which must be configured before the device has accounts added. This significantly limits deployment on already-provisioned devices.
- **Counter persistence** — The brute-force counter is stored in SharedPreferences and does not survive a wipe. It does survive a reboot.
- **ADB over USB** — The TCP-based ADB check may not reliably detect all ADB-over-USB configurations depending on kernel implementation.
- **No tamper protection** — If an attacker force-stops the app or revokes Device Administrator status before a threat is acted upon, the response will fail silently.
- **Polling latency** — Threat detection has up to a 1-second response delay by design.
- **Device Owner deactivation** — Once Device Owner is cleared via the in-app Deactivate button, it cannot be re-granted without either a factory reset or re-running the `adb shell dpm set-device-owner` command on a device with no accounts.

---

## License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for full terms.