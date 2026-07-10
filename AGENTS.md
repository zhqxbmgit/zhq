# AGENTS.md

## Project

Customized Android Moonlight / Artemis streaming client.

Main package:

```text
com.limelight
```

Current development baseline:

```text
Stable Baseline v1 — Batch 1 stability fixes
```

Primary Windows build command:

```powershell
.\gradlew.bat :app:assembleNonRoot_gameDebug
```

Expected Debug APK output:

```text
app\build\outputs\apk\nonRoot_game\debug\
```

---

## Working Rules

Before modifying code, run:

```powershell
git status
git branch --show-current
git diff --name-only
```

Do not execute these commands unless explicitly requested:

```powershell
git add .
git clean
git reset --hard
git commit
git push
git merge
```

Additional rules:

- Modify only files required for the current task.
- Do not reformat unrelated files.
- Do not overwrite user changes.
- Do not silently restore or revert files.
- Do not add APKs, `build/`, IDE caches, or `agent_reports/` to Git.
- Explain the intended design before a non-trivial state-machine or input-system change.
- Prefer the smallest correct change over broad refactoring.
- After code changes, run `git diff --check`.
- After code changes, build the Debug APK.
- Report the exact changed files and the first real build error, if any.
- Compilation success does not prove lifecycle or input-state correctness; provide an explicit real-device test plan.

---

## Product Priorities

Priority order:

1. Input correctness
2. Streaming-state reliability
3. Consistent touchpad feel
4. Backward compatibility
5. Performance
6. New features

Do not trade input correctness for visual cleanup or speculative optimization.

---

## Desktop Streaming Requirements

The app supports automatic PC selection and automatic Desktop streaming.

Required behavior:

- Auto-connect only to a paired and online PC.
- Respect the user setting that controls automatic Desktop streaming.
- Do not start Desktop using only stale cached app-list information.
- Wait for valid server information before deciding whether to start or resume.
- If no app is running, start `Desktop` / `桌面`.
- If Desktop is already running, resume it without duplicate launch requests.
- If another game is running, do not force-start Desktop.
- User-initiated disconnect or confirmed quit must suppress automatic resume.
- Home, lock screen, and background lifecycle interruptions may resume Desktop.
- Avoid duplicate `ServerHelper.doStart()` calls.

Any changes to `AppView.java`, `PcView.java`, or `Game.java` must be reviewed as lifecycle/state-machine changes.

---

## Touchpad Constraints

The touchpad is a relative-position touchpad, not a joystick.

Preserve:

- Second-order damping model
- `dampingRatio = 1.0` unless explicitly requested otherwise
- `carryOverX/Y` residual correction
- Inertial glide
- Click hold time
- Single click
- Double click
- Double-tap drag
- Existing sensitivity behavior
- Coordinate consistency between down, move, and up

Do not reintroduce the rejected first-order alpha-following mode:

```java
currentPos += (targetAccum - currentPos) * alpha;
```

Do not convert touch displacement into continuous joystick-style movement.

Do not make speculative touch-feel changes together with unrelated fixes.

Potential concurrency work must account for:

- Ticker identity and cancellation
- Delayed MouseUp tasks
- New touches interrupting old glide
- Multi-pointer handoff
- Shared executor behavior
- Activity cancellation and disconnect cleanup

---

## RelativeTouchContext Requirements

`touchDownEvent`, `touchMoveEvent`, and `touchUpEvent` must use the same coordinate system.

Any change must consider:

- View width or height being zero before layout
- View-size changes
- Orientation changes
- Split-screen
- Video scaling mode
- External display behavior

Do not change video-resolution scaling and touchpad smoothing parameters in the same task unless explicitly required.

---

## Virtual Controller Requirements

`SlideButton` and `SlideButtonLR` support:

- Tap
- Hold
- Slide
- Hold-to-slide takeover

Hold-to-slide takeover order must be:

```text
release base key
→ press slide key
→ release slide key on finger up
```

Do not allow a long-pressed base key to remain stuck after slide takeover.

Changes to controller input must consider:

- Multiple controls mapping to the same controller bit
- ACTION_CANCEL
- View removal or hiding
- Delayed release tasks
- Multi-touch
- Full-state reset on controller teardown

---

## Key Files

```text
app/src/main/java/com/limelight/PcView.java
app/src/main/java/com/limelight/AppView.java
app/src/main/java/com/limelight/Game.java

app/src/main/java/com/limelight/binding/input/touch/TrackpadContext.java
app/src/main/java/com/limelight/binding/input/touch/RelativeTouchContext.java

app/src/main/java/com/limelight/binding/input/virtual_controller/VirtualController.java
app/src/main/java/com/limelight/binding/input/virtual_controller/SlideButton.java
app/src/main/java/com/limelight/binding/input/virtual_controller/SlideButtonLR.java

app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java

app/src/main/res/xml/preferences.xml
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
```

---

## Required Final Report for Code Tasks

Provide:

1. Problem and root cause
2. Design used
3. Files changed
4. Important code-path changes
5. `git diff --check` result
6. Build command and result
7. APK output path
8. Remaining risks
9. Real-device regression checklist
