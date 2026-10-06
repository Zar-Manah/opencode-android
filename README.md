<p align="center">
  <img src="opencode-icon.png" width="128" height="128" alt="OpenCode Android Logo" />
</p>

# OpenCode Desktop-Style for Android 📱⚡

[![OpenCode Release](https://img.shields.io/github/v/release/Zar-Manah/opencode-android-apk?style=for-the-badge&color=blue)](https://github.com/Zar-Manah/opencode-android-apk/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%7C%20arm64--v8a-brightgreen?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![No Root Required](https://img.shields.io/badge/Root-NO%20ROOT%20REQUIRED-success?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Crafted with Passion in Spain](https://img.shields.io/badge/Crafted%20with%20Passion%20in-Spain%20%F0%9F%87%AA%F0%9F%87%B8-FFD700?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![License](https://img.shields.io/badge/License-GPL%20v3-yellow?style=for-the-badge)](LICENSE.md)

> **Plug-and-play OpenCode desktop-native app for Android: full device control & autonomous on-device APK creator (NO ROOT REQUIRED), persistent cognitive memory, 24/7 background daemon, and complete offline toolchain (JDK 21, Gradle, Android SDK). Crafted with passion in Spain 🇪🇸.**

---

## ⚡ The Zero-Friction Magic: Install & Code

Transform your Android smartphone or tablet into a state-of-the-art AI software engineering workstation in less than 60 seconds:

```
[ Download opencode.apk ] ➔ [ Tap Install ] ➔ [ Grant 4 Permissions ] ➔ [ Code with AI ]
```

1. **Download `opencode.apk`** from [GitHub Releases](https://github.com/Zar-Manah/opencode-android-apk/releases/latest).
2. **Install** the APK on your Android device (Android 8.0+ / `arm64-v8a`).
3. **Open the app** and complete the guided 4-step setup.
4. **You're ready!** OpenCode launches immediately into the native desktop-class interface. Works out of the box with free OpenCode models as well as custom API keys.

---

## 🌟 Why OpenCode Desktop for Android is Unique

Traditional mobile terminal ports were never built for full software engineering: slow container emulation, virtual keyboards that freeze, background tasks killed by power management, and sandboxes unable to interact with the device.

**OpenCode Desktop for Android redesigns the entire stack from the ground up:**

### 1. 🚀 Native Desktop-Style Architecture
- **Pure Native Execution**: The official OpenCode engine runs natively on Android user space via a high-performance musl dynamic linker (`ld-musl-aarch64`). No slow emulation containers, no PRoot filesystem overhead, and zero performance penalty.
- **Hardware-Accelerated UI**: Rich, fluid desktop-grade interface with full keyboard, clipboard, and touch support.
- **Instant Launch**: Starts in seconds with minimal memory footprint.

### 2. 📱 Full Phone Control (No Root Required)
OpenCode is not just an editor — it has hands and eyes on the mobile operating system up to the theoretical limit of unrooted Android:
- **Eyes (`pc shot` & `pc dump`)**: Real-time ultra-fast screenshot analysis (<0.3s) and XML UI accessibility hierarchy tree dumping.
- **Hands (`pc tap` & `pc swipe`)**: Can tap UI buttons, scroll through apps, and interact with native Android interfaces.
- **Typing (`pc text`)**: Injects text into active application text fields.
- **Navigation (`pc key BACK` / `pc key HOME`)**: Triggers standard Android navigation keys.
- **App Management**: Can launch apps (`pc open <package>`), query installed packages (`pc list`), read logs, and post notifications (`pc notify`).

### 3. 🏭 Autonomous On-Device Android App Factory 🔥
OpenCode can design, scaffold, compile, sign, install, and visually verify native Android applications directly on your phone:
- **Bundled Toolchain**: OpenJDK 21 (`java`, `javac`, `keytool`), Android SDK (platforms `android-35`, build-tools `35.0.0` and `34.0.0`, native `d8`, `apksigner`, `aapt2`), and `gradle-oc`.
- **Zero Network Required**: All compiler toolchains and core dependencies are pre-bundled inside the APK. No external PC, no USB cable, and no active Internet connection needed for compilation.
- **Autonomous Installation**: Installs compiled APKs directly onto the phone via the local bridge (`pc install app/build/outputs/apk/debug/*-debug.apk`).
- **Visual Verification Loop**: Takes a screenshot of the newly launched app (`pc shot /sdcard/Download/app.png`), analyzes the layout hierarchy (`pc dump`), and iterates on the code autonomously until it is pixel-perfect.

### 4. 🧠 Persistent Cognitive Architecture
Unlike stateless sessions that forget context when restarted:
- **Episodic Long-Term Memory**: Autonomous memory engine (`~/.config/opencode/memory/memory.json`) that records learnings, project capabilities, device configuration, and user preferences across sessions.
- **Self-Improving Agents**: OpenCode updates its memory after verifying builds and features, preserving patterns learned on the device.

### 5. 🎛️ Foreground Notification Control Panel (`Exit` | `24/7`) & Automatic Server Lifecycle
A streamlined 2-button control banner lives directly in your Android notification drawer for instantaneous state management without unnecessary clutter:

```
┌─────────────────────────────────────────────────────────┐
│ OpenCode                                                │
│ ┌─────────────────────────┐   ┌───────────────────────┐ │
│ │          Exit           │   │         24/7          │ │
│ └─────────────────────────┘   └───────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

* **⚡ Automatic Server Lifecycle (Zero Manual Intervention)**:
  The local OpenCode core engine listening on `127.0.0.1:4096` starts automatically upon app launch and manages its own lifecycle transparently. There is no longer any need to manually toggle or keep a "Server" button pressed. While the app is open, the AI agent has full device control, shell execution, and workspace access. When you close or exit the app, the server process shuts down cleanly and automatically, guaranteeing zero background battery drain or leftover processes.
* **⚡ `24/7` (Battery Saver & WakeLock Toggle)**:
  Controls the CPU `PARTIAL_WAKE_LOCK`. **Disabled by default** to keep your phone cold and conserve 100% of battery when the screen turns off. Tap to illuminate in emerald green when running intensive autonomous builds, large repo indexing, or overnight background agent tasks.
* **🛑 `Exit` (Clean Process Shutdown)**:
  One-tap graceful shutdown: terminates the local server process, releases all wake locks, clears notification banners, and cleans up background memory instantly.

---

## 🏗️ Architecture Overview

```
┌────────────────────────────────────────────────────────┐
│             Android System (Linux Kernel)              │
├──────────────────────────┬─────────────────────────────┤
│   OpenCode Host App      │   Native Accessibility      │
│   (a.opencode / UI)      │   Bridge Server (:4399)     │
├──────────────────────────┴─────────────────────────────┤
│             Native Android User Space                  │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Official OpenCode Engine (ld-musl-aarch64 :4096) │  │
│  │ Local Cognitive Layer (memory.json, AGENTS.md)   │  │
│  │ Device Bridge CLI (`pc`)                         │  │
│  │ Native Toolchain (JDK 21 + SDK 35/34 + gradle-oc)│  │
│  │ Workspace Root: ~/OpenCode (/sdcard/OpenCode)    │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 Guided Initial Setup

When launching OpenCode for the first time, a streamlined setup screen guides you through the 4 required permissions:

```
┌─────────────────────────────────────────────────────────┐
│                       OpenCode                          │
│ To grant OpenCode full device control, follow steps:    │
│                                                         │
│ [OpenCode]       Downloaded apps → OpenCode             │
│ [App info]       Allow restricted settings (3 dots ⋮)   │
│ [Allow files]    Manage all files                       │
│ [Allow installs] Install unknown apps                   │
│                                                         │
│ Bridge: connected  ·  Files: ok  ·  Installs: ok        │
└─────────────────────────────────────────────────────────┘
```

1. **`OpenCode` (`Downloaded apps → OpenCode`)**:
   Tap the button, select **Downloaded apps**, and select **OpenCode**. *(On Android 13+, you will initially see a message that the setting is restricted; tap it anyway so the 3 dots appear in the next step, then return).*
2. **`App info` (`Restricted settings`)**:
   Tap the button, tap the **3 dots (⋮)** in the top right corner, and select **'Allow restricted settings'**. Return to step 1 and enable OpenCode.
3. **`Allow files` (`Manage all files`)**:
   Tap the button to grant permission to manage all files. This allows OpenCode to read and write your workspace at `/storage/emulated/0/OpenCode`.
4. **`Allow installs` (`Install unknown apps`)**:
   Tap the button to allow installing apps created by OpenCode.

Once all four are granted, the status updates to:
`Bridge: connected · Files: ok · Installs: ok`
and OpenCode launches automatically!

---

## 🛠️ Built-in Tooling & Bridge Commands

OpenCode includes the `pc` device control CLI, accessible directly from the agent's environment:

| Command | Description |
|---|---|
| `pc ping` | Check device bridge status and health |
| `pc shot [path.png]` | Capture high-speed screenshot of current screen |
| `pc dump` | Dump active window accessibility view hierarchy XML |
| `pc tap <x> <y>` | Send touch tap to screen coordinate |
| `pc swipe <x1> <y1> <x2> <y2> [ms]` | Send smooth swipe gesture across screen |
| `pc text <string>` | Type text into the active input field |
| `pc key <BACK\|HOME>` | Trigger Android system navigation keys |
| `pc open <package>` | Launch application by package name |
| `pc install <path.apk>` | Autonomously install APK on device |
| `pc shell <command>` | Execute shell commands with application permissions |
| `pc list [filter]` | Query installed packages on the device |
| `pc notify <message> [title]` | Post system notification banner |
| `gradle-oc <tasks>` | On-device mksh-safe Gradle runner |

---

## 💻 Autonomous App Development Workflow

1. **Scaffold Project**: The agent creates the project structure in `~/OpenCode/<ProjectName>/` (symlinked to `/storage/emulated/0/OpenCode/<ProjectName>/`).
2. **Build**: Compiles on-device with `gradle-oc :app:clean assembleDebug`.
3. **Install**: Deploys autonomously via `pc install app/build/outputs/apk/debug/*-debug.apk`.
4. **Launch & Verify**: Opens the app with `pc open <package>`, captures screenshots with `pc shot /sdcard/Download/<name>.png`, and inspects UI hierarchy with `pc dump`.

---

## 🔒 Security & Privacy

- **100% Local-First**: Runs directly on your device. Zero telemetry.
- **No Root Required**: Operates strictly within standard unrooted Android user space.
- **Transparent Open Source**: All source code and build recipes are publicly verifiable.

---

## 🇪🇸 Crafted with Passion in Spain

OpenCode for Android is an open-source initiative developed and published with ❤️ from Madrid, Spain by **Zar-Manah**.

---

## 📄 License

Licensed under the [GNU General Public License v3.0](LICENSE.md).
