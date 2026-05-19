# Numa — Activation & Usage Guide

This guide covers every step required to make Numa fully operational after APK installation, from granting permissions to the first use.

---

## Before you start

- Numa APK is installed on the device
- USB debugging is enabled (if using adb commands)
- Device runs Android 8.0 (API 26) or higher

---

## Step 1 — Grant full storage access

Numa needs to read and securely delete the protected folder on external storage.

### Via adb
```bash
adb shell appops set com.numa MANAGE_EXTERNAL_STORAGE allow
```

### Via device settings
**Settings → Apps → Numa → Permissions → Files and media → Allow management of all files**

> On some devices: **Settings → Privacy → Special app access → All files access → Numa → Enable**

---

## Step 2 — Enable the Accessibility Service

This is the most critical permission. It allows Numa to intercept volume key presses with no visible interface.

### Via adb
```bash
adb shell settings put secure enabled_accessibility_services com.numa/.NumaAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Via device settings
**Settings → Accessibility → Installed services → Numa → Enable**

A system confirmation dialog will appear — accept it.

> **Note:** The app may briefly close when this is activated. This is normal — Android restarts the process.

---

## Step 3 — Disable battery optimization

Without this, Android may kill the Numa service in the background, especially when the screen is off.

### Via adb
```bash
adb shell dumpsys deviceidle whitelist +com.numa
```

### Via device settings
**Settings → Battery → Battery optimization → All apps → Numa → Don't optimize**

> On some OEM devices (Samsung, Xiaomi, Huawei): **Settings → Battery → App launch / Auto-start → Numa → Allow**

---

## Step 4 — Verify services are running

### Via adb
```bash
adb shell dumpsys activity services com.numa
```

In the output, you should see:

```
User 0 active services:
  * ServiceRecord{...} com.numa/.NumaAccessibilityService
```

`crashCount` must be `0` and the service must appear under **"active services"**, not **"Restarting services"**.

---

## Step 5 — Create the protected folder

Manually create the folder on the device's external storage. By default it is named `Safe` (configurable via `FOLDER_NAME` in `.env`).

```
/storage/emulated/0/Safe/
```

Place the files you want to protect inside it. Numa uses this folder as the source for PANIC.

---

## Step 6 — Open the app once

Numa must be opened at least once to start `NumaService`.

- Open it from the launcher (it briefly appears then shows nothing)
- Or via adb:

```bash
adb shell am start -n com.numa/.MainActivity
```

After this first launch, Numa survives device reboots thanks to `NumaBootReceiver`.

---

## Usage — Key sequences

Both sequences must be entered in **under 3 seconds**, with at least **200ms between each press**.

### PANIC — Backup + secure wipe
**Vol+ → Vol− → Vol+ → Vol+**

1. Confirm vibration: 2 short pulses (100ms · 50ms pause · 100ms)
2. Operation runs (a few seconds depending on folder size)
3. Success: 3 short vibrations + toast `"numa panic ok"`
4. Failure: 1 strong long vibration (local folder preserved if upload failed)

### RESTORE — Download + decrypt
**Vol− → Vol+ → Vol− → Vol−**

1. Confirm vibration: 1 long pulse (300ms)
2. Download + decryption in progress
3. Success: 3 short vibrations + toast `"numa restore ok"`
4. Failure: 1 strong long vibration

---

## Validity period (VALID_PERIOD)

If a validity period is configured in `.env`, the countdown starts at the **first successful PANIC**.

After expiry:
- Any PANIC or RESTORE attempt shows the toast `"numa expiré"` + error vibration
- No operation is executed

| Value | Duration |
|-------|----------|
| `5mins` | 5 minutes |
| `3hrs` | 3 hours |
| `7jrs` | 7 days |
| `2smns` | 2 weeks |
| `6mois` | 6 months |
| `1ans` | 1 year |
| *(empty)* | No expiry |

---

## Monitoring logs (debug)

To see what Numa is doing in real time:

```bash
# PANIC / RESTORE operations only
adb logcat -s NumaOp:D

# All Numa tags
adb logcat -s NumaOp:D NumaService:D CryptoManager:D R2Uploader:D ZipManager:D SecureWipe:D

# Crashes only
adb logcat -b crash
```

---

## Activation checklist

| Step | Via adb | Via device settings | Required |
|------|---------|---------------------|----------|
| Full file access | `appops set com.numa MANAGE_EXTERNAL_STORAGE allow` | Settings → Apps → Numa → Permissions | Yes |
| Accessibility service | `settings put secure enabled_accessibility_services ...` | Settings → Accessibility → Numa | Yes |
| Battery not optimized | `dumpsys deviceidle whitelist +com.numa` | Settings → Battery → Numa → Don't optimize | Recommended |
| Create Safe folder | `adb shell mkdir /storage/emulated/0/Safe` | File manager | Yes |
| First launch | `adb shell am start -n com.numa/.MainActivity` | Open the app | Yes |

---

## Troubleshooting

**App does not respond to sequences:**
- Verify the accessibility service is active (Step 2)
- Run `dumpsys activity services com.numa` and check `crashCount=0`

**Toast "numa expiré" appears:**
- The `VALID_PERIOD` has elapsed
- The app must be rebuilt with a new or empty `VALID_PERIOD`

**PANIC succeeded but Safe folder is still present:**
- Multi-pass secure wipe can take several seconds on large folders
- Check logs with `adb logcat -s NumaOp:D`

**R2 receives a 50-byte file:**
- `MANAGE_EXTERNAL_STORAGE` is not granted (Step 1)
- Re-run Step 1 commands and retry

**Service is under "Restarting services":**
- A crash occurred — check `adb logcat -b crash`
- Reinstall the APK and restart from Step 2
