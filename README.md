<p align="center">
  <img src="icon-card.png" width="128" alt="OpenCode icon" />
</p>

# OpenCode for Android 🤖📱

[![GitHub Release](https://img.shields.io/github/v/release/Zar-Manah/opencode-android?color=7C3AED&style=for-the-badge&logo=android)](https://github.com/Zar-Manah/opencode-android/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android)](https://github.com/Zar-Manah/opencode-android)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-blue?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android)
[![No Root Required](https://img.shields.io/badge/Root-NO%20ROOT%20REQUIRED-success?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android)
[![Crafted in Spain](https://img.shields.io/badge/Crafted%20in-Spain%20%F0%9F%87%AA%F0%9F%87%B8-FFD700?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android)
[![License](https://img.shields.io/badge/License-GPL%20v3-yellow?style=for-the-badge)](LICENSE)

> **Plug and play OpenCode app for Android: full device control and APK creator (NO ROOT REQUIRED), persistent cognitive memory, 24/7 daemon mode, a fully autonomous AI coding agent on your phone. Crafted in Spain 🇪🇸.**

---

## ⚡ The Zero-Friction Magic: Install & Code

Transform your Android smartphone or tablet into a state-of-the-art AI software engineering workstation in less than 60 seconds:

```
[ Download opencode.apk ] ➔ [ Tap Install ] ➔ [ Grant Permissions ] ➔ [ Code with AI ]
```

1. **Download `opencode.apk`** from [GitHub Releases](https://github.com/Zar-Manah/opencode-android/releases/latest).
2. **Install** the APK on your Android device (Android 8.0+ / arm64-v8a).
3. **Open the app** and follow the guided setup (Storage & Accessibility Bridge).
4. **You're ready!** OpenCode launches immediately into the interactive chat TUI. No complex terminal setup, no manual package compiling, and zero configuration headaches. Works out of the box with free OpenCode models as well as custom API keys.

---

## 🌟 Why OpenCode for Android is Unique

Traditional mobile terminal ports were never built for AI agents: virtual keyboards freeze, voice dictation drops inputs, background tasks get killed by aggressive Android power management, and agents are trapped in isolated sandboxes unable to see or interact with the operating system.

**OpenCode for Android solves every single one of these problems:**

### 1. ⌨️ Smooth Virtual Keyboard & Voice Dictation
- **Completely Fixed Input Engine**: Native virtual keyboards (Gboard, Samsung Keyboard, SwiftKey) work seamlessly. No key-repeats, no frozen input buffers, and no missed keystrokes.
- **Full Voice-to-Text Support**: Dictate prompts, code refactors, or terminal commands using your keyboard's microphone button without breaking terminal cursor positions or hanging the process.

### 2. 🧠 Persistent Cognitive Architecture (Autonomous Memory & Subconscious)
Unlike stateless CLI sessions that forget everything once restarted, OpenCode for Android incorporates a local cognitive architecture:
- **Episodic Long-Term Memory**: Autonomous memory engine (`/root/.opencode/memory.json`) that records learnings, key context, project goals, and user preferences.
- **Contextual Recall (BM25)**: Re-injects relevant past context automatically based on conversation turns.
- **Nightly Dream Consolidation (3:30 AM)**: Automatic background cron jobs consolidate recent events, remove redundancy, update system goals, and keep memory clean.
- **Morning Digest (8:00 AM)**: Summarizes accomplishments and prepares project goals for the upcoming day.

### 3. 📱 Desktop-Class Agent with Full Phone Control (Zero Root)
OpenCode is not just confined to a terminal — it has hands and eyes on your mobile operating system up to the theoretical limit of unrooted Android:
- **Eyes (`pc shot` & `pc dump`)**: Real-time ultra-fast screenshot analysis (<0.4s) and XML UI accessibility hierarchy tree dumping.
- **Hands (`pc tap` & `pc swipe`)**: Can tap UI buttons, scroll through apps, and interact with native Android interfaces.
- **Typing (`pc text`)**: Injects text into active application text fields.
- **App Management**: Can launch apps (`pc open <package>`), read notifications, and trigger system intents.
- **Native Android App Factory**: The agent can scaffold Jetpack Compose / Kotlin / Gradle projects, compile APKs, and invoke on-device installation autonomously!

### 4. 🔋 24/7 Background Daemon & Quick-Action Banner
Android aggressively kills background processes. OpenCode includes an optimized notification control center:
- **`24/7 Server` Action Pill**: Toggles high-performance wake-locks and background server mode, turning your phone into a persistent 24/7 coding server (similar to a background desktop daemon).
- **`Exit` Action Pill**: Cleanly terminates background processes and releases system resources with a single tap.

---

## 🏗️ Architecture Overview

OpenCode for Android combines a robust native Android container harness with a complete Debian Linux distribution:

```
┌────────────────────────────────────────────────────────┐
│             Android System (Linux Kernel)              │
├──────────────────────────┬─────────────────────────────┤
│   OpenCode Host App      │   Native Accessibility      │
│   (a.opencode / UI)      │   Bridge Server (:4399)     │
├──────────────────────────┴─────────────────────────────┤
│         PRoot Debian Linux Container (Trixie)          │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Node.js 24 + OpenCode CLI + Git + Dev Tools      │  │
│  │ Local Cognitive Layer (Memory, Crons, Prompts)   │  │
│  │ Bridge CLI (`pc`, `adb`, `free`, `crontab`)      │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

- **Target SDK**: Optimized for modern Android (ColorOS, OneUI, HyperOS, Stock AOSP) with bypass for restrictive `W^X` memory protections.
- **PRoot Debian Subsystem**: A complete, native Debian userland containing Node.js 24, Python 3, OpenJDK, Git, ZSH with autosuggestions and syntax highlighting.
- **Accessibility & IPC Bridge**: High-speed localhost loopback communication between the containerized AI agent and Android OS services.

---

## 🚀 Quick Start Guide

### Step 1: Download & Install
Grab the latest release from the [Releases tab](https://github.com/Zar-Manah/opencode-android/releases/latest) or run via ADB:
```bash
adb install -r opencode.apk
```

### Step 2: Grant Permissions
To grant OpenCode full device control (taps, keys, screen capture, and app installation), follow these steps:
1. Tap '1. Go to Accessibility' and select OpenCode — at first you'll get a message that you can't select it, but it's important you tap it anyway so the three dots from step 2 appear, then return here.
2. Tap '2. Unlock (3 dots)', tap the 3 dots (⋮) in the top right corner, and select 'Allow restricted settings'. Then go back to step one and now it will let you select OpenCode.
3. Tap '3. Files Access' to grant permission to manage all files.
4. Return and tap 'Continue to OpenCode' if it doesn't open automatically.

### Step 3: Start Coding
On first launch, wait a couple of minutes and the OpenCode terminal interface will initialize automatically. Simply type your prompt, pick your model, and start building!

---

## 🛠️ Built-in Tooling & Commands

Inside the OpenCode shell, you have access to a rich set of mobile automation and development tools:

| Command | Description |
|---|---|
| `opencode` / `oc` | Launch the OpenCode AI coding assistant |
| `pc shot [output.png]` | Capture high-speed screenshot of current screen |
| `pc dump` | Dump UI hierarchy tree (XML) of the active app |
| `pc tap <x> <y>` | Tap specific coordinate on screen |
| `pc swipe <x1> <y1> <x2> <y2> [ms]` | Perform swipe gesture |
| `pc text "<text>"` | Type text into currently focused input |
| `pc key <HOME\|BACK\|POWER>` | Send hardware key event |
| `pc open <package\|url>` | Launch application or open web link |
| `pc notify "<msg>" "[title]"` | Send Android system notification |
| `pc list [filter]` | List installed applications on device |

---

## 🇪🇸 Crafted with Passion in Spain

**OpenCode for Android** is conceived, engineered, and published from Spain 🇪🇸 by **Zar-Manah**. 

Our vision is to break the hardware boundary of modern software engineering: empowering developers, students, researchers, and creators worldwide to carry a complete, self-sustaining AI software engineering powerhouse directly in their pocket.

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are very welcome!
- Check out our [Issues](https://github.com/Zar-Manah/opencode-android/issues) tab.
- Submit Pull Requests with improvements or documentation enhancements.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)** — see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  <b>Built for the future of mobile AI software engineering.</b><br>
  <sub>OpenCode is an independent open-source project. Not affiliated with Google, Termux, or Android.</sub>
</p>
