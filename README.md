# Numa — Android Silent Data Protection App

An **invisible** Android background application that monitors volume key sequences to trigger encrypted backup (PANIC) or restore (RESTORE) operations against Cloudflare R2.

> **Legal notice**: Personal use only. Do not use on a device you do not own or without explicit consent. The author accepts no liability for illegal use.

---

## How it works

Numa runs silently as a background service with no visible UI and no launcher icon. A minimal silent notification is required by Android (foreground service constraint) but is hidden from the lock screen and placed in the "silent" notification section. It intercepts volume key presses via an `AccessibilityService` and triggers operations when a specific sequence is entered within 3 seconds.

**Supported scenarios:** screen on (normal use), screen on + locked.

| Operation | Sequence | Confirmation vibration |
|-----------|----------|------------------------|
| **PANIC** — encrypt + upload + secure wipe | Vol+, Vol−, Vol+, Vol+ | 2 short pulses (100ms · 50ms · 100ms) |
| **RESTORE** — download + decrypt + unzip | Vol−, Vol+, Vol−, Vol− | 1 long pulse (300ms) |

**End-of-operation feedback:**
- Success → 3 short vibrations + toast `"numa panic ok"` / `"numa restore ok"`
- Failure → 1 strong long vibration

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Node.js | 18+ |
| Java JDK | 17+ |
| Android SDK | API 26+ (Android 8.0) |
| EAS CLI | `npm i -g eas-cli` |

---

## Project setup

```bash
git clone <repo>
cd numa-mobile-app
npm install
cp .env.example .env
# Fill in .env with your values
```

---

## Environment configuration (.env)

```env
# Folder to protect — located at /storage/emulated/0/<FOLDER_NAME>
FOLDER_NAME=Safe

# AES-256 encryption key — exactly 64 hex characters (32 bytes)
# Generate with: openssl rand -hex 32
ENCRYPTION_KEY=

# Cloudflare R2 credentials
R2_ACCOUNT_ID=
R2_ACCESS_KEY_ID=
R2_SECRET_ACCESS_KEY=
R2_BUCKET_NAME=numa-backup
R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com

# Secure wipe passes (DoD 5220.22-M standard = 7)
WIPE_PASSES=7

# Validity period — starts at first successful PANIC
# Units: mins (minutes), hrs (hours), jrs (days), smns (weeks), mois (months), ans (years)
# Examples: 5mins | 3hrs | 7jrs | 2smns | 6mois | 1ans
# Leave empty for no expiry
VALID_PERIOD=
```

---

## Generate an ENCRYPTION_KEY

**Via command line:**
```bash
openssl rand -hex 32
```

**Via the React Native bridge (after install):**
```javascript
const { NumaModule } = require('react-native').NativeModules;
NumaModule.generateKey().then(console.log);
```

The key must be exactly **64 hex characters** (256 bits). Keep it safe — without it, encrypted data is permanently unrecoverable.

---

## Cloudflare R2 setup

1. Go to [dash.cloudflare.com](https://dash.cloudflare.com) → R2 Object Storage
2. Create a bucket (e.g. `numa-backup`)
3. Go to **Manage R2 API Tokens** → Create Token
   - Permissions: **Object Read & Write** on your bucket
   - Copy `Access Key ID` and `Secret Access Key` into `.env`
4. Set `R2_ENDPOINT` to `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`

---

## Build

### Generate the Android project

```bash
npx expo prebuild --clean
```

The config plugin automatically copies all Kotlin sources and XML resources into `android/` and injects all `.env` values as `BuildConfig` fields.

### Register NumaPackage (one-time, after prebuild)

In [android/app/src/main/java/com/numa/MainApplication.kt](android/app/src/main/java/com/numa/MainApplication.kt), inside `getPackages()`:

```kotlin
override fun getPackages(): List<ReactPackage> =
    PackageList(this).packages.apply {
        add(NumaPackage())
    }
```

### Development build (USB device)

```bash
npx expo run:android
```

### Production APK (local)

```bash
cd android && ./gradlew assembleRelease
# Output: android/app/build/outputs/apk/release/app-release.apk
```

---

## Post-install setup

See [use_numa/use_numa_en.md](use_numa/use_numa_en.md) for the complete step-by-step activation guide (permissions, accessibility service, battery optimization).

**Quick reference via adb:**

```bash
# 1. Grant storage access (Android 11+)
adb shell appops set com.numa MANAGE_EXTERNAL_STORAGE allow

# 2. Enable AccessibilityService
adb shell settings put secure enabled_accessibility_services com.numa/.NumaAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 3. Disable battery optimization
adb shell dumpsys deviceidle whitelist +com.numa

# 4. Verify services are running
adb shell dumpsys activity services com.numa
```

---

## Architecture

```
plugins/
├── withNuma.js                      # Expo Config Plugin
└── src/
    ├── kotlin/
    │   ├── NumaService.kt               # Background service + WakeLock
    │   ├── NumaAccessibilityService.kt  # Volume key interception
    │   ├── VolumeKeyReceiver.kt         # Sequence detection + haptics
    │   ├── NumaOperationService.kt      # PANIC / RESTORE execution
    │   ├── CryptoManager.kt             # AES-256-GCM encryption
    │   ├── SecureWipe.kt                # Multi-pass secure deletion
    │   ├── R2Uploader.kt                # Cloudflare R2 client (AWS SigV4)
    │   ├── ZipManager.kt                # ZIP compression / decompression
    │   ├── NumaBootReceiver.kt          # Auto-start on boot
    │   ├── NumaModule.kt                # React Native bridge
    │   └── NumaPackage.kt               # RN package registration
    ├── res/xml/
    │   └── numa_accessibility_service.xml
    └── res/drawable/
        └── numa_transparent.xml         # Transparent icon (silent notification)
src/
└── index.js                         # Minimal headless JS entry point
```

### PANIC flow

```
Vol+−++ sequence detected (< 3s)
  → Confirm vibration (100ms · 50ms · 100ms)
  → Check MANAGE_EXTERNAL_STORAGE permission
  → ZIP /storage/emulated/0/<FOLDER_NAME> → cacheDir
  → AES-256-GCM encrypt ZIP → cacheDir
  → Secure wipe ZIP (7 passes)
  → Upload encrypted file to R2 (ETag MD5 verified)
  → Secure wipe encrypted file
  → Secure wipe source folder (7 passes)
  → Record first-PANIC timestamp (validity period start)
  → Success: 3 vibrations + toast "numa panic ok"
  → Failure: strong vibration (folder preserved if upload failed)
```

### RESTORE flow

```
Vol−+−− sequence detected (< 3s)
  → Confirm vibration (300ms)
  → Download encrypted file from R2 → cacheDir
  → AES-256-GCM decrypt → cacheDir
  → Secure wipe encrypted file
  → Unzip to /storage/emulated/0/<FOLDER_NAME>
  → Secure wipe ZIP
  → Success: 3 vibrations + toast "numa restore ok"
  → Failure: strong vibration
```

### Validity period

When `VALID_PERIOD` is set, the timer starts at the first successful PANIC. Any subsequent PANIC or RESTORE attempt after expiry shows a `"numa expiré"` toast and triggers an error vibration without executing the operation.

Supported units: `5mins`, `3hrs`, `7jrs`, `2smns`, `6mois`, `1ans`.

---

## Security

| Property | Detail |
|----------|--------|
| Algorithm | AES-256-GCM |
| IV | 96-bit random, generated per encryption |
| File format | `[12 bytes IV][ciphertext + 16 bytes GCM tag]` |
| Key derivation | Raw 256-bit key from 64-char hex string |
| Temp files | Created in `context.cacheDir` (internal storage, no root access) |
| Remote filename | SHA-256 hash of `FOLDER_NAME` (non-revealing) |
| Wipe standard | 7-pass DoD-style: `0x00, 0xFF, 0x00, random, 0xAA, 0x55, random` |
| Wipe integrity | `fsync()` forced after every pass |
| Upload integrity | ETag MD5 checksum verified before wiping local files |
| Safety rule | Local folder is **never wiped** if upload fails |
| Logging | Keys and sensitive data only logged when `BuildConfig.DEBUG = true` |
