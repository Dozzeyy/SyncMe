# SyncMe - WebDAV Cloud Sync

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-26%20%28Android%208.0%2B%29-green.svg)](https://developer.android.com/about/versions/oreo)
[![Android Target SDK](https://img.shields.io/badge/Target%20SDK-37-brightgreen.svg)](https://developer.android.com/about/versions/16)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Compose-Material%203-blue.svg)](https://developer.android.com/jetpack/compose)

**SyncMe** is a private and secure Android application designed to synchronize your local files, folders, and essential device data (Contacts, Messages, Call Logs) to any WebDAV-supported cloud provider with client-side end-to-end encryption (E2EE).

---

## ✨ Features

- **☁️ Universal WebDAV Cloud Support**  
  Seamlessly syncs with Nextcloud, ownCloud, Fastmail, Synology NAS, box.com, or any standard WebDAV cloud server.

- **🔒 Client-Side End-to-End Offline Encryption (E2EE)**  
  Encrypts files locally on your device using AES-256-GCM before uploading to the cloud. Key derivation uses PBKDF2 with salt. Your encryption passphrase never leaves your device.

- **📱 Complete Device Data Backup**  
  Backup essential Android device data directly to the cloud or local storage:
  - **Contacts**
  - **Messages** (SMS)
  - **Call Logs**

- **📁 Flexible Folder & File Sync**  
  Select single files or entire directory trees using Android's Storage Access Framework (SAF / DocumentFile).

- **⏱️ Automated Background Scheduling**  
  Schedule automated sync tasks (Hourly, Daily, Weekly, or Manual) powered by Android Jetpack `WorkManager`.

- **🛡️ Enterprise Security & Privacy**  
  - Credentials stored in hardware-backed `Android KeyStore` via `EncryptedSharedPreferences`.
  - Strict WebDAV origin validation and path containment (prevention of path traversal & cross-origin credential/data leaks).
  - Hardened XXE-safe WebDAV XML parser.
  - Zero cleartext HTTP traffic allowed.
  - Backups disabled at OS level to prevent unauthorized extraction.


Check App screenshots here
<div style="display: flex; justify-content: center; gap: 10px; flex-wrap: wrap;">
  <img src="https://raw.githubusercontent.com/Dozzeyy/SyncMe/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screen 1" width="200">
  <img src="https://raw.githubusercontent.com/Dozzeyy/SyncMe/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Screen 2" width="200">
  <img src="https://raw.githubusercontent.com/Dozzeyy/SyncMe/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screen 3" width="200">
  <img src="https://raw.githubusercontent.com/Dozzeyy/SyncMe/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Screen 4" width="200">
</div>

<div style="display: flex; justify-content: center; gap: 10px; flex-wrap: wrap;">
  <img src="https://raw.githubusercontent.com/Dozzeyy/SyncMe/main/fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" alt="Screen 1" width="200"> 
</div>
---

## 🛠️ Architecture & Tech Stack

- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose) with [Material 3](https://m3.material.io/) design system
- **Dependency Injection:** [Dagger Hilt](https://dagger.dev/hilt/)
- **Database & Persistence:** [Room Persistence Library](https://developer.android.com/training/data-storage/room)
- **Networking:** [OkHttp 4](https://square.github.io/okhttp/) with strict TLS enforcement and redirect validation
- **Background Processing:** [AndroidX WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- **Cryptography:** Custom AES-256-GCM container engine + [AndroidX Security Crypto](https://developer.android.com/topic/security/data)
- **JSON Processing:** [KotlinX Serialization](https://github.com/Kotlin/kotlinx.serialization)

---

##  Building & Running

### Prerequisites

- Android Studio Ladybug (2024.2.1+) or IntelliJ IDEA with Android plugin
- JDK 17 or JDK 21
- Android SDK 37 (API Level 37)

### Build Steps

1. **Clone the Repository:**
   ```bash
   git clone https://github.com/your-username/SyncMe.git
   cd SyncMe
   ```

2. **Run Unit Tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```

3. **Assemble Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```
   The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

4. **Assemble Release APK:**
   ```bash
   ./gradlew assembleRelease
   ```

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request or open an issue for feature requests and bug reports.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📜 License

Distributed under the **GNU General Public License v3.0** (`GPL-3.0-or-later`). See `LICENSE` for more information.

```text
SyncMe - WebDAV Cloud Sync
Copyright (C) 2026 Bhuvan (app.upstream242@passmail.com)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```

---

## 📧 Contact

**Author:** Bhuvan  
**Email:** `app.upstream242@passmail.com`
