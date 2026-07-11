# README_DEV.md

# Moonlight / Artemis Android Custom Project

## Project Status

Current branch:

```text
moonlight-noir
```

Current baseline:

```text
Stable Baseline v1
```

Development status:

```text
Long-term development
```

---

# Project Goal

This project is based on the Android Moonlight / Artemis client.

Main goals:

- Automatic connection to paired PCs
- Automatic Desktop streaming
- Reliable automatic resume
- High-quality relative touchpad
- Stable touch feeling
- Customized virtual controller
- Long-term maintainable architecture

This is NOT the standalone Wi-Fi DS4 controller project.

---

# Build

Debug build:

```powershell
.\gradlew.bat :app:assembleNonRoot_gameDebug
```

APK output:

```text
app/build/outputs/apk/nonRoot_game/debug/
```

---

# Development Documents

Read these before modifying code:

```text
AGENTS.md
PROJECT_HISTORY.md
ARCHITECTURE.md
```

Responsibilities:

| File | Purpose |
|------|---------|
| AGENTS.md | Development rules |
| PROJECT_HISTORY.md | Development history |
| ARCHITECTURE.md | Project architecture |

---

# Main Modules

```text
PcView.java
```

Responsible for:

- PC selection
- Pairing
- Entering AppView

---

```text
AppView.java
```

Responsible for:

- App list
- Desktop auto start
- Desktop auto resume
- Streaming state coordination

---

```text
Game.java
```

Responsible for:

- Streaming Activity
- Lifecycle
- Input routing
- Orientation

---

```text
TrackpadContext.java
```

Responsible for:

- Relative touchpad
- Second-order damping
- Glide
- Click handling

---

```text
RelativeTouchContext.java
```

Responsible for:

- Coordinate scaling
- Stream resolution mapping

---

```text
VirtualController.java
```

Responsible for:

- Controller state
- Button packets
- Stick packets

---

```text
SlideButton.java
SlideButtonLR.java
```

Responsible for:

- Hold
- Slide
- Hold-to-slide takeover

---

# Current Priorities

Priority:

```text
P0
```

AppView automatic Desktop state machine

---

```text
P1
```

Trackpad concurrency

- ticker lifecycle
- delayed MouseUp
- multi-pointer handoff

---

```text
P2
```

VirtualController

- shared button ownership
- releaseAllInputs()

---

```text
P3
```

Portrait mode

---

# Development Workflow

Before every task:

```powershell
git status
git branch --show-current
git diff --check
```

Read:

```text
AGENTS.md
PROJECT_HISTORY.md
ARCHITECTURE.md
```

Then:

1. Analyze
2. Explain root cause
3. Explain design
4. Wait for approval
5. Modify code
6. Build
7. Real-device test
8. Commit
9. Push

---

# Commit Rule

Every completed feature:

```powershell
git add <modified files>
git commit -m "<clear description>"
```

Never use:

```powershell
git add .
```

unless explicitly requested.

---

# Real Device First

Compilation success does NOT mean the feature is complete.

Every change must pass:

- Build
- Install
- Real-device test

before commit.

---

# Current Stable Features

Completed:

- Stable touchpad damping
- Carry-over correction
- RelativeTouchContext coordinate fix
- SlideButton long-press takeover fix
- Auto Desktop setting
- Desktop auto resume
- Batch 1 stability fixes
- Development documentation

---

# Known Issues

Highest priority:

```text
AppView automatic Desktop state coordination
```

Needs continued verification and optimization.

---

# Long-term Goal

Maintain a stable, high-quality Android streaming client.

Development principles:

- Stability first
- Small, reviewable changes
- Architecture before implementation
- Real-device verification
- Clean Git history