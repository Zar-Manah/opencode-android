# OpenCode — Autonomous AI Coding Agent for Android

OpenCode is an autonomous on-device AI coding agent running natively in Android user space.

## Device Environment & Architecture

- **Operating System**: Android (Linux kernel + Android runtime)
- **Home Directory**: `$HOME` (`/data/user/0/a.opencode/files/server-home`)
- **Workspace Root**: `~/OpenCode` (symlinked directly to `/storage/emulated/0/OpenCode`)
- **Native Toolchain**:
  - JDK 21: `$HOME/toolchain/usr/lib/jvm/java-21-openjdk` (wrappers in `~/bin/java`, `~/toolchain/bin/java`)
  - Android SDK: `$HOME/android-sdk` (`ANDROID_HOME` & `ANDROID_SDK_ROOT` pre-configured in environment)
  - SDK Platforms: `android-35`
  - Build Tools: `35.0.0` and `34.0.0` (with native `d8`, `apksigner`, `aapt2`)
  - Gradle Runner: `gradle-oc` (in PATH, mksh-safe wrapper for on-device compilation)
  - Native utilities: `git`, `aapt2`, `apksigner`, `iconos` in `$HOME/bin`, `$HOME/toolchain/bin`, and PATH
  - Global Gradle config: `~/.gradle/gradle.properties` includes `android.suppressUnsupportedCompileSdk=35`
- **Device Control Bridge**:
  - Local HTTP bridge active at `http://127.0.0.1:4399` via the `pc` CLI tool.
  - Commands available to the agent:
    - `pc ping`: Check bridge health and status
    - `pc shot [path.png]`: Capture current screen framebuffer to PNG
    - `pc dump`: Dump active window accessibility view hierarchy XML
    - `pc tap <x> <y>`: Send touchscreen tap event
    - `pc swipe <x1> <y1> <x2> <y2> [duration_ms]`: Perform gesture swipe/scroll
    - `pc text <string>`: Type text into active input field
    - `pc key <BACK|HOME>`: Trigger navigation keys
    - `pc open <package_name>`: Launch application
    - `pc install <path_to_apk>`: Autonomously install APK on device
    - `pc shell <command>`: Execute shell commands with application permissions
    - `pc list [filter]`: Query installed packages
    - `pc notify <message> [title]`: Post system notification

## Autonomous Application Development Workflow

1. **Project Creation**: Create new native Android projects inside `~/OpenCode/<ProjectName>/` (or `/storage/emulated/0/OpenCode/<ProjectName>/`).
2. **Build & Package**: Compile on-device with `gradle-oc :app:clean assembleDebug`.
3. **On-Device Installation**: Install the compiled APK directly onto this device using `pc install app/build/outputs/apk/debug/*-debug.apk`.
4. **Launch & Verification**:
   - Launch with `pc open <package_name>`.
   - Autonomously verify the application UI by capturing screenshots (`pc shot /sdcard/Download/<name>.png`) and reading view hierarchy (`pc dump`).
   - Interact with the app via `pc tap`, `pc swipe`, and `pc text`.
   - Inspect runtime logs via `pc shell "logcat -d | grep <tag>"`.

## Local Persistent Memory

OpenCode maintains its persistent memory locally on this device in `~/.config/opencode/memory/memory.json`.
Update memory with verified project capabilities, device configuration, and learned patterns across sessions.
