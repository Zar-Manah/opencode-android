# OpenCode phone-ops — Native Device Control on Android / Debian

You are inside the Debian environment of OpenCode on Android.
You have full, native device control through the native bridge (`pc`, available directly in your PATH).
It does not depend on ADB, WiFi, or USB, and survives device reboots permanently.

## Native Device Control (`pc` Tool)
- **General Status**: `pc status` (native bridge, battery, screen state, current focus, adb).
- **View Screen**: `pc shot` (instant capture in <150ms to `/bridge/shot.png`).
- **Inspect UI**: `pc dump` (dumps hierarchical view tree to `/bridge/dump.json` in <20ms).
- **Tap Screen**: `pc tap <X> <Y>` (tap at given coordinates).
- **Swipe Screen**: `pc swipe <X1> <Y1> <X2> <Y2> [MS]` (smooth scrolling and gestures).
- **Click by Text/Id**: `pc click "text"` or `pc clickid "id"` (locates and directly taps element).
- **Scroll**: `pc scroll down` or `pc scroll up`.
- **System Keys**: `pc key HOME`, `pc key BACK`, `pc key RECENTS`, `pc key NOTIFS`, `pc key POWER`, `pc key VOLUP`, `pc key VOLDOWN`.
- **Type Text**: `pc text "message to type"` (into focused input field).
- **Open Apps/URLs**: `pc open com.example.app` or `pc open https://example.com`.
- **Toasts**: `pc toast "message"`.
- **Clipboard**: `pc clipboard get` and `pc clipboard set "text"`.
- **Installed Apps**: `pc list` (list third-party packages) or `pc list <filter>`.
- **Install Apps (Native)**: `pc install <file.apk>` (auto-confirms installation, cable-free).
- **Uninstall Apps**: `pc uninstall <package>` (auto-confirmed native uninstallation).
- **Background Control**: `pc wake` (requests WakeLock) and `pc server` (activates sshd/wakelock server).
- **ADB Compatibility**: `adb` in PATH automatically routes to `pc` if ADB 5555 is down after reboot (`adb devices`, `adb install`, `adb shell input tap`, etc. work transparently).
- **Android Shell**: `pc shell <command...>` (direct execution on Android host).

## System Paths
- HOME=/root; workspace: `/root/opencode` (`opencode`/`oc` alias opens directly here).
- Quick exchange folder: `/bridge` (symlinks to screenshots and dumps).
- Android storage: `/sdcard` or home storage links (`storage/shared`, `storage/downloads`).
- Node 24: `/opt/node/bin`. OpenCode: `/root/.opencode/bin/opencode` (or `/opt/node/bin/opencode`).

## Execution Guidelines
- The device stays in sleep mode by default: do not run heavy background tasks without explicit user instruction.
- Zero external dependencies or unnecessary remote servers: all automation and control execute locally.
