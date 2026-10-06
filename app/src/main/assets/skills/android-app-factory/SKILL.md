---
name: android-app-factory
description: Native Android application factory (Kotlin + Gradle) that compiles directly on this device, installs locally, and verifies visually via screenshots. Use to create, build, sign, and install Android apps without an external computer.
---

# android-app-factory — On-Device Android App Factory (Kotlin + Gradle)

Methodology to design, code, build, sign, install, and verify native Android applications by running the complete pipeline entirely on this device (native OpenCode Android app).

If the toolchain is not yet installed, start with step 0. This only needs to run once per device.

---

## 0. Toolchain Setup (Once Per Device)

```bash
open-factory-setup
```

Installs and verifies: JDK 21, Gradle runner `gradle-oc` (in system PATH), Android SDK in `$HOME/android-sdk` (platforms android-35, build-tools 35.0.0 and 34.0.0 with d8, apksigner), and native ARM `aapt2` with override in `$HOME/.gradle/gradle.properties`.

Fixed toolchain paths:
- **JAVA_HOME**: `$HOME/toolchain/usr/lib/jvm/java-21-openjdk`
- **Gradle**: `gradle-oc` in the system PATH (`~/bin/gradle-oc`, `~/toolchain/bin/gradle-oc`)
- **SDK**: `$HOME/android-sdk` (`ANDROID_HOME` and `ANDROID_SDK_ROOT` are pre-set in environment)
- **aapt2 ARM override**: configured in `$HOME/.gradle/gradle.properties`
- **git, aapt2, apksigner, d8**: in `$HOME/bin/` and `$HOME/toolchain/bin/`

GOLDEN RULE: Never export `LD_LIBRARY_PATH` globally in the environment (it affects system binaries). Wrappers in `$HOME/bin` and `$HOME/toolchain/bin` configure paths internally per process.

---

## 1. Canonical Project Structure

```
MyProject/
├── settings.gradle
├── build.gradle
├── gradle.properties              # org.gradle.jvmargs=-Xmx3g, android.suppressUnsupportedCompileSdk=35
├── local.properties               # sdk.dir=$HOME/android-sdk (optional since ANDROID_HOME is exported)
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/author/myproject/
```

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MyProject"
include(":app")
```

Root `build.gradle` (pinned and verified versions: AGP 8.5.2 + Kotlin 1.9.24):

```groovy
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3g
android.suppressUnsupportedCompileSdk=35
```

`app/build.gradle`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.author.myproject"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.author.myproject"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
```

---

## 2. Compile and Install On-Device

```bash
cd ~/OpenCode/MyProject
gradle-oc :app:clean assembleDebug
```

Install autonomously on this device via local bridge (127.0.0.1:4399):

```bash
pc install app/build/outputs/apk/debug/*-debug.apk
```

Launch the installed application:

```bash
pc open com.author.myproject
```

Notes:
- Always clean before building: the device filesystem may retain stale bytecode classes.
- The initial compilation caches dependencies in `$HOME/.gradle/caches`.
- Keep `minifyEnabled false` in debug builds.
- Device bridge handles the package installer session autonomously.

---

## 3. Autonomous Visual Verification

Never guess how the application appears; inspect it directly:

```bash
pc shot /sdcard/Download/app.png
pc dump
pc shell "logcat -d | grep TAG_OF_THE_APP"
```

Verify visual components, tap with `pc tap <x> <y>`, scroll with `pc swipe <x1> <y1> <x2> <y2>`, and type text with `pc text "<text>"`.

---

## 4. Adaptive Icons and Release Signing

Generate adaptive icons using the bundled JDK tool (minimum 1024x1024 base image):

```bash
iconos base.png app/src/main/res/
```

Declare in `res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

Generate release signing key (`keytool` is in `$JAVA_HOME/bin`):

```bash
keytool -genkeypair -keystore release.keystore -alias appkey -keyalg RSA -keysize 2048 -validity 10000
gradle-oc assembleRelease
```

To export the APK:

```bash
cp app/build/outputs/apk/release/*-release.apk /sdcard/Download/
```

---

## 5. Performance Guidelines

- Request highest refresh rate (120 Hz) in `MainActivity.onCreate`:

```kotlin
try {
    val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION") window.windowManager.defaultDisplay
    }
    display?.supportedModes?.maxByOrNull { it.refreshRate }?.let {
        window.attributes = window.attributes.apply { preferredDisplayModeId = it.modeId }
    }
} catch (_: Throwable) {}
```

- Zero unnecessary latency on touch event dispatch.
- Persistent background tasks should run as Foreground Services with notification channels.

---

## 6. Real-Time Logs

```bash
pc shell "logcat -d | grep TAG_OF_THE_APP"
```
