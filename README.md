<p align="center">
  <img src="opencode-icon.png" width="128" height="128" alt="OpenCode Android Logo" />
</p>

# OpenCode APK for Android 📱⚡

[![OpenCode Release](https://img.shields.io/github/v/release/Zar-Manah/opencode-android-apk?style=for-the-badge&color=blue)](https://github.com/Zar-Manah/opencode-android-apk/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%7C%20arm64--v8a-brightgreen?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![No Root Required](https://img.shields.io/badge/Root-NO%20ROOT%20REQUIRED-success?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Crafted with Passion in Spain](https://img.shields.io/badge/Crafted%20with%20Passion%20in-Spain%20%F0%9F%87%AA%F0%9F%87%B8-FFD700?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![License](https://img.shields.io/badge/License-GPL%20v3-yellow?style=for-the-badge)](LICENSE.md)

> **The first fully autonomous, native AI software engineering workstation and Android app factory directly on your phone. Full device control (NO ROOT REQUIRED), flawless virtual keyboard & voice dictation, zero emulation overhead, persistent cognitive memory. Crafted with passion in Spain 🇪🇸.**

---

## ⚡ The Zero-Friction Magic: Install & Code

Transform your Android smartphone or tablet into a state-of-the-art AI software engineering workstation in less than 60 seconds:

```
[ Download opencode.apk ] ➔ [ Tap Install ] ➔ [ Grant 4 Permissions ] ➔ [ Code with AI ]
```

1. **Download `opencode.apk`** from [GitHub Releases](https://github.com/Zar-Manah/opencode-android-apk/releases/latest).
2. **Install** the APK on your Android device (Android 8.0+ / `arm64-v8a`).
3. **Open the app** and follow the streamlined 4-step setup.
4. **You're ready!** OpenCode launches immediately into the native interface. Works out of the box with free OpenCode models as well as custom API keys.

---

## 🌟 Why OpenCode for Android Solves Every Mobile Pain Point

Anyone who has tried coding or running AI agents on Android knows the frustrating reality of existing solutions: virtual keyboards freeze and drop characters, voice dictation crashes the terminal, aggressive Android battery management kills long-running background tasks, PRoot containers run agonizingly slow and overheat the phone, agents are trapped in isolated sandboxes unable to interact with the device, and you still need a desktop PC to compile native apps.

**OpenCode APK for Android was engineered to solve every single one of these problems:**

### 1. ⌨️ Flawless Virtual Keyboard & Voice-to-Text Dictation
- **Zero Input Glitches**: Works effortlessly with native virtual keyboards (Gboard, Samsung Keyboard, SwiftKey) and external hardware keyboards. No repeated key bugs, no input buffer freezing, and no cursor jumping.
- **Native Voice Dictation**: Dictate prompts, architectural plans, or code refactors directly using your keyboard's microphone button without breaking prompt positions or crashing the session.
- **Intuitive Touch Controls**: Seamless multiline editing, full clipboard copy-paste, and natural touch navigation.

### 2. 🚀 Pure Native arm64 Execution (Zero PRoot / Container Overhead)
- **Direct User-Space Linker**: Runs natively on Android ARM64 via a high-performance musl dynamic linker (`ld-musl-aarch64`). Completely eliminates slow PRoot containers and `ptrace` system call emulation penalties.
- **Cool & Battery-Friendly**: Keeps your phone completely cool and preserves battery during normal development sessions, delivering instant launch times (<1s) and fluid responsiveness.

### 3. 📱 Full Device Interaction Without Root (NO ROOT REQUIRED)
OpenCode breaks out of the sandbox to provide true agentic capability on Android up to the theoretical limit of unrooted operating systems:
- **Eyes (`pc shot` & `pc dump`)**: Real-time ultra-fast screenshot analysis (<0.3s) and complete accessibility view hierarchy XML inspection.
- **Hands (`pc tap` & `pc swipe`)**: Taps UI buttons, navigates menus, and scrolls through applications.
- **Typing & Navigation (`pc text` & `pc key`)**: Types into active text fields and sends system navigation keys (Back, Home).
- **Application Control**: Launches apps (`pc open <package>`), lists installed packages (`pc list`), reads logs, and delivers notifications (`pc notify`).

### 4. 🏭 Autonomous On-Device Android App Factory 🔥
Turn your phone into a self-contained mobile software factory capable of designing, scaffolding, compiling, signing, installing, and visually verifying native Android applications:
- **Bundled Toolchain**: Pre-packaged OpenJDK 21, Android SDK (platforms `android-35`, build-tools `35.0.0` and `34.0.0`, native `d8`, `apksigner`, `aapt2`), and `gradle-oc`.
- **Zero PC Required for Builds**: Compiles and signs APKs entirely on-device without an external computer.
- **Autonomous Deployment & Inspection Loop**: Installs the compiled APK via the local bridge (`pc install`), launches the app, captures screenshots (`pc shot`), analyzes the UI (`pc dump`), and iterates until code and interface are pixel-perfect.

### 5. 🧠 Persistent Cognitive Memory Architecture
Unlike stateless terminal sessions that forget all context when closed:
- **Episodic Long-Term Memory**: Autonomous memory engine (`~/.config/opencode/memory/memory.json`) preserves project context, device characteristics, architectural decisions, and user preferences across sessions.
- **Continuous Learning**: Retains successful patterns and verification strategies learned on the device.

### 6. 🔋 24/7 Background Daemon & Clean Notification Center
Android's aggressive memory cleaner terminates background tasks. OpenCode includes an optimized foreground notification control center:

```
┌─────────────────────────────────────────────────────────┐
│ OpenCode                                                │
│ ┌─────────────────────────┐   ┌───────────────────────┐ │
│ │          Exit           │   │         24/7          │ │
│ └─────────────────────────┘   └───────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

- **⚡ `24/7` (Battery Saver & WakeLock Toggle)**: Keeps the CPU awake via partial wake-lock. Disabled by default to preserve 100% of battery when the screen is off; tap to activate for intensive background builds, repository indexing, or overnight agent runs.
- **🛑 `Exit` (Clean Shutdown)**: Instant one-tap graceful termination: frees all background processes, releases system locks, removes notifications, and restores RAM.

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
   Tap the button, select **Downloaded apps**, and select **OpenCode**. *(On Android 13+, tap through the restricted settings prompt if displayed).*
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
