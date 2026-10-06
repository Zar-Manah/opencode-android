<p align="center">
  <img src="opencode-icon.png" width="128" height="128" alt="OpenCode Android Logo" />
</p>

# OpenCode APK for Android 📱⚡

[![OpenCode Release](https://img.shields.io/github/v/release/Zar-Manah/opencode-android-apk?style=for-the-badge&color=blue)](https://github.com/Zar-Manah/opencode-android-apk/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%7C%20arm64--v8a-brightgreen?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![No Root Required](https://img.shields.io/badge/Root-NO%20ROOT%20REQUIRED-success?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Crafted with Passion in Spain](https://img.shields.io/badge/Crafted%20with%20Passion%20in-Spain%20%F0%9F%87%AA%F0%9F%87%B8-FFD700?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![License](https://img.shields.io/badge/License-GPL%20v3-yellow?style=for-the-badge)](LICENSE.md)

> **Plug-and-play OpenCode APK for Android: the first fully autonomous, native AI software engineering powerhouse on your phone. Full device control & on-device APK factory (NO ROOT REQUIRED), flawless virtual keyboard & voice dictation, persistent cognitive memory, 24/7 background daemon, and complete offline toolchain (JDK 21, Gradle, Android SDK). Crafted with passion in Spain 🇪🇸.**

---

## ⚡ The Zero-Friction Magic: Install & Code

Transform your Android smartphone or tablet into a state-of-the-art AI software engineering workstation in less than 60 seconds:

```
[ Download opencode.apk ] ➔ [ Tap Install ] ➔ [ Grant 4 Permissions ] ➔ [ Code with AI ]
```

1. **Download `opencode.apk`** from [GitHub Releases](https://github.com/Zar-Manah/opencode-android-apk/releases/latest).
2. **Install** the APK on your Android device (Android 8.0+ / `arm64-v8a`).
3. **Open the app** and complete the guided 4-step setup.
4. **You're ready!** OpenCode launches immediately into the native interface. Works out of the box with free OpenCode models as well as custom API keys.

---

## 🌟 Why OpenCode APK for Android is Unique

Traditional mobile terminal ports were never built for serious software engineering: virtual keyboards freeze, voice dictation drops inputs, background tasks get killed by aggressive Android power management, container emulation slows down compilation, and agents remain trapped in isolated sandboxes unable to see or interact with the operating system.

**OpenCode APK for Android solves every single one of these problems:**

### 1. ⌨️ Smooth Virtual Keyboard & Voice Dictation
- **Native Keyboard Integration**: Gboard, Samsung Keyboard, SwiftKey, and physical keyboards work seamlessly. No key-repeats, no frozen input buffers, and no missed keystrokes.
- **Full Voice-to-Text Support**: Dictate complex prompts, code refactors, or terminal commands using your mobile keyboard's microphone button without breaking cursor positions or hanging the interface.
- **Effortless Mobile Editing**: Fluid multiline editing, full clipboard support, smooth scrolling, and mobile-friendly touch interactions.

### 2. 🚀 Pure Native Execution (Zero Emulation Overhead)
- **Direct User-Space Performance**: Runs natively on Android ARM64 via an ultra-fast musl dynamic linker (`ld-musl-aarch64`). No heavy PRoot virtualization, no emulated system calls, and minimal RAM footprint.
- **Cool & Battery Efficient**: Keeps your phone completely cool during regular usage, conserving battery while delivering instantaneous response times.
- **Instant App Launch**: Starts in seconds with zero container boot delay.

### 3. 📱 Full Phone Control (No Root Required)
OpenCode is not confined to an isolated sandbox — it has hands and eyes on the mobile operating system up to the theoretical limit of unrooted Android:
- **Eyes (`pc shot` & `pc dump`)**: Real-time high-speed screenshot capture (<0.3s) and XML UI accessibility hierarchy tree inspection.
- **Hands (`pc tap` & `pc swipe`)**: Tap UI buttons, scroll through apps, and interact with native Android applications.
- **Typing & Navigation (`pc text` & `pc key`)**: Type directly into active inputs and trigger system navigation keys (Back, Home).
- **App Management**: Launch installed apps (`pc open <package>`), query packages (`pc list`), read logs, and post system notifications (`pc notify`).

### 4. 🏭 Autonomous On-Device Android App Factory 🔥 (100% Offline)
OpenCode can design, scaffold, compile, sign, install, and visually verify native Android applications directly on your phone:
- **Pre-bundled Toolchain**: OpenJDK 21 (`java`, `javac`, `keytool`), Android SDK (platforms `android-35`, build-tools `35.0.0` and `34.0.0`, native `d8`, `apksigner`, `aapt2`), and `gradle-oc`.
- **Zero Internet Required for Builds**: All compilers and core dependencies are pre-packaged inside the APK. No external PC, no USB cables, and no active network connection required to compile apps.
- **Autonomous Deployment**: Installs compiled APKs directly onto the phone via the local bridge (`pc install app/build/outputs/apk/debug/*-debug.apk`).
- **Visual Verification Loop**: Takes a screenshot of the newly launched app (`pc shot`), analyzes layout hierarchy (`pc dump`), and iterates on the code autonomously until pixel-perfect.

### 5. 🧠 Persistent Cognitive Architecture
Unlike stateless sessions that forget context when restarted:
- **Episodic Long-Term Memory**: Autonomous memory engine (`~/.config/opencode/memory/memory.json`) that records learnings, project capabilities, device configuration, and user preferences across sessions.
- **Self-Improving Agents**: OpenCode updates its memory after verifying builds and features, preserving patterns learned on the device.

### 6. 🔋 24/7 Background Daemon & Quick-Action Banner
Android aggressively kills background processes. OpenCode includes an optimized foreground notification control center:

```
┌─────────────────────────────────────────────────────────┐
│ OpenCode                                                │
│ ┌─────────────────────────┐   ┌───────────────────────┐ │
│ │          Exit           │   │         24/7          │ │
│ └─────────────────────────┘   └───────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

- **⚡ `24/7` (Battery Saver & WakeLock Toggle)**: Controls the CPU `PARTIAL_WAKE_LOCK`. Disabled by default to conserve 100% of battery when the screen turns off. Tap to illuminate in green when running intensive autonomous builds, large repo indexing, or overnight agent tasks.
- **🛑 `Exit` (Clean Shutdown)**: One-tap graceful shutdown: terminates background processes, releases all wake locks, removes notifications, and frees system RAM cleanly.

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

**OpenCode APK for Android** is conceived, engineered, and published from Madrid, Spain 🇪🇸 by **Zar-Manah**.

Our vision is to break the hardware boundary of modern software engineering: empowering developers, students, researchers, and creators worldwide to carry a complete, self-sustaining AI software engineering powerhouse directly in their pocket.

---

## 📄 License

Licensed under the [GNU General Public License v3.0](LICENSE.md).
