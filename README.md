# RedX Linux

A real Linux terminal for Android — **no root required**.

Built in Kotlin. Runs **Alpine Linux** via **PRoot**, giving you a genuine Linux environment with `apk`, `bash`, `python`, `git`, `curl`, `nmap`, and everything you can install via Alpine's package manager — just like Termux or Kali NetHunter, but your own.

---

## Features

- ✅ Real Alpine Linux 3.19 (not simulated, not fake)
- ✅ PRoot-based — works on **non-rooted** Android devices
- ✅ Full ANSI/VT100 terminal emulator written in Kotlin
- ✅ Special key bar: ESC, CTRL, TAB, arrow keys, PgUp/PgDn
- ✅ APK under 10 MB (Alpine ~3 MB downloaded on first launch)
- ✅ Persistent filesystem — your files survive app restarts
- ✅ Supports ARM64, ARMv7, x86_64
- ✅ Install any package: `apk add python3 git curl nmap vim`
- ✅ Dark terminal theme (classic green-on-black)

---

## How it works

1. **First launch**: Downloads PRoot binary (~1 MB) and Alpine Linux minirootfs (~3 MB)
2. Extracts Alpine to your app's private storage
3. Launches `/bin/ash` inside PRoot — a real Linux shell with full syscall support
4. Your session is completely isolated; no root access needed

---

## Building

### Requirements
- Android Studio Flamingo or newer
- JDK 17
- Android SDK 34

### Build locally
```bash
chmod +x gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### CI / GitHub Actions
Every push to `main` automatically:
1. Compiles the Kotlin code
2. Builds both Debug and Release APKs
3. Uploads them as build artifacts

---

## First-run setup (on device)

1. Install the APK
2. Open the app — tap **Install Alpine Linux**
3. Wait ~30 seconds for download + extraction (~3 MB)
4. Tap **Launch Terminal**
5. You're in Alpine Linux. Try:

```sh
apk update
apk add bash python3 git curl
python3 --version
```

---

## Install security tools (Kali-style)

```sh
apk add nmap netcat-openbsd tcpdump curl wget
apk add python3 py3-pip
pip3 install requests scapy
```

---

## Architecture

```
app/
├── terminal/
│   ├── TerminalEmulator.kt   # VT100/VT220 state machine
│   ├── TerminalView.kt       # Canvas-based renderer + keyboard
│   └── TerminalSession.kt    # Process lifecycle + I/O
├── core/
│   ├── BootstrapManager.kt   # Downloads & extracts Alpine + PRoot
│   └── ProotManager.kt       # Builds PRoot command
└── service/
    └── TerminalService.kt    # Foreground service (keeps session alive)
```

---

## License

MIT — fork it, extend it, make it yours.
