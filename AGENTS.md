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

## Development Workflow

Before any modification, run:

```powershell
git status
git branch --show-current
git diff --check
git diff --name-only
```

Before changing code:

1. Read the relevant source files.
2. Explain the root cause.
3. Explain the proposed design.
4. List the files that need to change.
5. Identify likely side effects.
6. Wait for confirmation before making non-trivial state-machine, lifecycle, concurrency, or input-system changes.

After modifying code:

```powershell
git diff --check
git diff --name-only
.\gradlew.bat :app:assembleNonRoot_gameDebug
```

Every completed task must report:

1. Root cause
2. Design used
3. Files changed
4. Important behavior changes
5. Side effects and remaining risks
6. `git diff --check` result
7. Build command and result
8. APK output path
9. Real-device regression checklist

Compilation success does not prove lifecycle, concurrency, or input-state correctness. Always provide a real-device test plan.

---

## Git Safety

Do not execute these commands unless explicitly requested:

```powershell
git add .
git clean
git reset --hard
git commit
git push
git merge
git rebase
git restore
```

Rules:

- Do not stage or commit files unless explicitly requested.
- Never use `git add .`.
- Do not overwrite user changes.
- Do not silently restore files.
- Do not delete untracked files.
- Do not push directly to a remote without confirmation.
- Do not use force push.
- Do not modify Git history without explicit approval.
- Do not add APKs, `build/`, IDE caches, or `agent_reports/` to Git.
- If Git reports an unsafe repository, only trust the specific project path; never use `safe.directory "*"`.
- The expected local repository path may be:

```text
C:\zhq
```

The current branch should be checked before each task.

---

## Documentation Rules

The following files are project documents:

```text
AGENTS.md
PROJECT_HISTORY.md
ARCHITECTURE.md
```

Do not modify them unless explicitly requested.

When a major feature or stability fix is completed and real-device testing passes:

- Update `PROJECT_HISTORY.md`
- Update `ARCHITECTURE.md` only if architecture actually changed
- Keep `AGENTS.md` concise and rule-focused

The actual source code is always authoritative. Documentation provides context but must not override current implementation.

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
- Virtual-display confirmation must not appear repeatedly from duplicate callbacks.
- A pending launch state must not become a permanent lockout after cancellation, failure, or a later valid retry.

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

Do not introduce:

- Adaptive sensitivity
- Velocity-dependent gain
- Dynamic damping
- Unrequested acceleration curves
- Unrequested smoothing modes

Do not make touch-feel changes together with unrelated fixes.

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

Do not fix shared-key ownership using isolated `|=` or `&= ~` patches without reviewing the aggregate input-state model.

---

## Orientation and Resolution

Existing settings include automatic screen orientation and automatic resolution inversion.

Always-forced portrait is a cross-cutting feature.

Any portrait-mode change must consider:

- Runtime orientation requests in `Game.java`
- `rotateScreen()`
- `setPreferredOrientationForActivity()`
- `onConfigurationChanged()`
- Decoder width/height inversion
- Video scaling
- `RelativeTouchContext`
- Virtual-controller layout
- Automatic resume
- External displays

Do not implement forced portrait only through `AndroidManifest.xml`.

Do not modify orientation, stream resolution, touch scaling, and touchpad smoothing parameters in the same task unless explicitly requested.

---

## Scope Control

- Modify only files required for the current task.
- Do not reformat unrelated files.
- Do not rename unrelated symbols.
- Do not reorganize packages without approval.
- Do not upgrade Gradle, AGP, SDK, NDK, Java, or dependencies unless explicitly requested.
- Do not change application ID, signing, build variants, or release configuration unless explicitly requested.
- Prefer the smallest correct change over broad refactoring.
- Preserve backward compatibility where practical.

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

## Real-Device Testing Expectations

For streaming-state changes, test:

- Cached app list present
- No app running
- Desktop already running
- Another game running
- Automatic Desktop setting disabled
- Repeated Home/return
- Lock/unlock
- Background/foreground
- Explicit disconnect
- Quit confirmation: Yes and No
- Virtual-display confirmation: confirm and cancel
- No duplicate Game Activity or duplicate confirmation dialog

For touchpad changes, test:

- Fine movement
- Large movement
- Fast direction changes
- Single click
- Rapid clicks
- Double click
- Double-tap drag
- Glide
- New touch interrupting old glide
- Long-duration use
- Multi-pointer transitions
- Different stream resolutions and orientations

For virtual-controller changes, test:

- Tap
- Hold
- Slide
- Hold-to-slide takeover
- ACTION_CANCEL
- Concurrent controls
- Shared-key mappings
- Controller hide/remove/disconnect
- No stuck buttons, sticks, or triggers
