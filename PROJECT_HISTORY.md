# PROJECT_HISTORY.md

# Moonlight / Artemis Android Custom Project History

## 1. Project Scope

This repository is a customized Android Moonlight / Artemis streaming client.

Primary goals:

- Automatically connect to a paired and online PC
- Automatically start or resume Windows Desktop streaming
- Use the Android device as a high-quality relative touchpad
- Improve touchpad smoothness, damping, and precision
- Customize Moonlight's on-screen virtual controller
- Recover reliably after Home, lock screen, or background transitions

This is not the separate standalone Wi-Fi DS4 controller project.

Do not mix in:

- A standalone Windows controller receiver
- A separate Android-to-Windows controller protocol
- Windows-side DS4 emulation packaging
- Phone battery reporting to a standalone receiver
- Release plans for the separate controller application

---

## 2. Build

Primary build variant:

```text
nonRoot_gameDebug
```

Windows PowerShell build command:

```powershell
.\gradlew.bat :app:assembleNonRoot_gameDebug
```

Typical APK output:

```text
app\build\outputs\apk\nonRoot_game\debug\
```

---

## 3. Stable Baseline v1

Date:

```text
2026-07-10
```

Git commit:

```text
Baseline: stability fix batch 1
```

This commit is the official development baseline for subsequent work.

The baseline includes previous custom touchpad and virtual-controller changes, plus the first batch of stability fixes.

---

## 4. Implemented Features

### 4.1 Automatic PC Entry

Implemented behavior:

```text
launch app
→ find a paired and online PC
→ enter its app list
```

Current limitation:

- In a multi-PC environment, the first matching PC may be selected.
- A future improvement may prefer the UUID of the last successful PC.

### 4.2 Automatic Desktop Streaming

Implemented intent:

```text
enter AppView
→ identify Desktop / 桌面
→ start or resume Desktop
```

A user preference controls whether this automatic behavior is enabled.

Desktop name matching currently includes:

```text
Desktop
桌面
```

### 4.3 Automatic Resume

Implemented intent:

- Resume after Home
- Resume after lock/unlock
- Resume after background interruption
- Do not resume after user-initiated disconnect or confirmed quit

The project uses:

```java
Game.terminatedByUser
```

to distinguish user termination from lifecycle interruption.

---

## 5. Stability Fix Batch 1

### 5.1 RelativeTouchContext Coordinate Consistency

Modified file:

```text
app/src/main/java/com/limelight/binding/input/touch/RelativeTouchContext.java
```

Original problem:

```text
touchDownEvent used unscaled coordinates
touchMoveEvent used scaled coordinates
touchUpEvent used unscaled coordinates
```

Because `TrackpadContext` calculates displacement relative to the original touch point, mixing coordinate systems could cause jumps or inconsistent movement.

Fix:

- Down, move, and up now use one consistent scaled coordinate system.

Status:

```text
Build passed
Real-device test passed
```

### 5.2 Game Quit Confirmation

Modified file:

```text
app/src/main/java/com/limelight/Game.java
```

Original problem:

- `terminatedByUser` was set before the user confirmed quitting.
- Selecting “No” could still suppress later automatic resume.

Fix:

- `terminatedByUser = true` is set only after confirmed quit.
- Explicit disconnect still marks user termination.

Status:

```text
Build passed
Real-device test passed
```

### 5.3 AppView Server-Information Gate

Modified file:

```text
app/src/main/java/com/limelight/AppView.java
```

Original goal:

- Prevent stale cached app-list data from starting Desktop before real server state is known.

Fix direction:

- Add a server-information gate before automatic Desktop launch.

Status:

```text
Build passed
Initial real-device test passed
```

Known regression discovered later:

```text
launch app
→ enter app selection screen
→ automatic Desktop stream does not start
```

Likely cause:

- Cached app list is processed while `receivedServerInfo == false`.
- The first automatic-start attempt is skipped.
- When server information arrives, the launch decision is not reliably retried in every unchanged-state path.

This remains the first unresolved high-priority issue after Stable Baseline v1.

---

## 6. Touchpad Design History

### 6.1 Current Model

`TrackpadContext.java` uses a second-order damping model.

Key state:

```java
targetAccumX
targetAccumY
currentPosX
currentPosY
currentVelX
currentVelY
lastSentX
lastSentY
carryOverX
carryOverY
```

The damping ratio should remain:

```java
double dampingRatio = 1.0;
```

A value greater than `1.0` caused excessive lag:

```text
finger reaches target
→ mouse continues chasing target
```

### 6.2 Known Parameter Baseline

Last known values were approximately:

```java
private static final float LINEAR_SPEED_MULTIPLIER = 7.0f;
private static final int TICK_RATE_MS = 4;
private static final double SMOOTHING_TIME_CONSTANT = 0.035;
private static final double MAX_VELOCITY = 15000.0;
private static final double MAX_ACCELERATION = 80000.0;
private static final double GLIDE_DECELERATION = 120000.0;
private static final double POS_THRESHOLD = 0.5;
private static final double VEL_THRESHOLD = 2.0;
```

The actual source code is authoritative. Do not overwrite values from this document without checking the current file.

### 6.3 Carry-Over Correction

Residual movement below one integer mouse unit is preserved.

Required order:

```text
calculate rounded delta
→ update lastSentX/Y
→ calculate carryOverX/Y
```

Do not calculate carry-over before updating `lastSentX/Y`.

### 6.4 Inertial Glide

Finger-up enters glide mode and decelerates with:

```java
GLIDE_DECELERATION
```

The glide is intentional and should not be removed without explicit approval.

### 6.5 Click and Drag

Current behavior includes:

- Single click
- Delayed MouseUp to avoid frame-polled click loss
- Double click
- Double-tap drag
- Click vibration

Typical click hold time:

```text
approximately 25 ms
```

### 6.6 Rejected First-Order Smoothing

A first-order alpha-following mode was implemented and tested:

```java
currentPos += (targetAccum - currentPos) * alpha;
```

Observed behavior:

- Floating feel
- Fine-control jitter
- Sticky tracking
- Worse damping character
- Inferior to the second-order implementation

Decision:

```text
Rejected. Do not reintroduce without an explicit research task.
```

---

## 7. Virtual Controller History

### 7.1 SlideButton

Supports:

- Tap
- Long press
- Slide up
- Slide down
- Long-press-to-slide takeover

### 7.2 SlideButtonLR

Supports:

- Tap
- Long press
- Slide left
- Slide right
- Slide up
- Long-press-to-slide takeover

### 7.3 Fixed Stuck-Key Bug

Original failure:

```text
base key enters long press
→ finger slides
→ slide key is pressed
→ base key remains pressed
→ finger up releases only slide key
```

Fix:

```text
release base key
→ clear longPressActive
→ press slide key
→ release slide key on finger up
```

Status:

```text
Build passed
Real-device test passed
```

### 7.4 Known Shared-Key Ownership Risk

Multiple on-screen controls may map to the same controller bit.

Example:

```text
independent A button holds A
B slide-down also maps to A
B slide-down releases A
→ independent A may be released incorrectly
```

Potential architecture:

- Per-input-source button masks
- Per-button reference counts
- Central input-state aggregator

This issue is not yet formally fixed.

---

## 8. Screen Orientation and Resolution

Existing settings include:

```text
Automatic screen orientation
Automatic resolution inversion
```

Automatic orientation means:

```text
use device orientation at stream start
```

It does not mean:

```text
always force portrait
```

Always-forced portrait is not yet a verified stable feature.

Any future implementation must consider:

- Runtime orientation requests in `Game.java`
- `rotateScreen()`
- `setPreferredOrientationForActivity()`
- Decoder width/height inversion
- RelativeTouchContext scaling
- Virtual-controller layout
- Resume behavior
- External-display behavior

Do not implement portrait locking only in the Manifest.

---

## 9. Known Issues After Stable Baseline v1

### P0 — AppView Automatic Streaming Can Stop at App Selection

Observed:

```text
open app
→ automatic PC entry succeeds
→ AppView opens
→ Desktop stream does not start
```

Likely state-machine defect:

- Real server information arrives after cached app-list handling.
- A launch attempt skipped before server information is not always retried.

Next work should unify start/resume coordination and prevent duplicate launch requests.

### P1 — Trackpad Ticker Cancellation Race

Potential sequence:

```text
old ticker decides to stop
→ new touch starts a new ticker
→ old ticker cancels the shared current future
→ new movement stops unexpectedly
```

Candidate solution:

- Generation token or task identity
- Old tick may stop only itself
- Synchronize ticker creation and cancellation

### P1 — Delayed MouseUp Race

Potential sequence:

```text
single click schedules delayed MouseUp
→ new touch or drag starts
→ old MouseUp fires during new gesture
→ new drag is released
```

Candidate solution:

- Track the scheduled MouseUp future
- Coordinate cancellation on new gesture
- Cancel during `cancelTouch()` and teardown

### P1 — Multi-Pointer Handoff

Review required:

- Second pointer movement
- Primary pointer lift and secondary takeover
- Reinitialization of touch origin
- Duplicate movement or click generation
- Empty `setPointerCount()` behavior

### P1 — Virtual Controller Shared-Key Ownership

A release from one control may clear a bit still held by another control.

### P2 — Full Input Reset

Controller hide, removal, activity teardown, or disconnect should release:

- Buttons
- Sticks
- Triggers
- Delayed tasks

A centralized `releaseAllInputs()` may be required.

### P2 — Repeated Preference Reads

Some input and drawing paths repeatedly call:

```java
PreferenceConfiguration.readPreferences(context)
```

Potential optimization:

- Cache frequently used settings
- Refresh on preference changes
- Avoid full preference reconstruction in hot paths

---

## 10. Next Development Targets

### Batch 2 — Highest Priority

1. Fix and simplify AppView automatic Desktop state coordination
2. Confirm no duplicate `ServerHelper.doStart()` calls
3. Confirm other running games are never replaced by Desktop
4. Verify repeated Home/lock/background resume
5. Add explicit real-device regression tests

### Batch 3 — Input Reliability

1. Trackpad ticker identity
2. Delayed MouseUp lifecycle
3. Multi-pointer handoff
4. Controller shared-key ownership
5. Full input reset on teardown

### Later Features

- Always-force portrait mode
- Last-used-PC preference
- Performance cleanup
- Additional automated tests

---

## 11. Test Checklist

### Automatic Streaming

- [ ] No running app: Desktop starts
- [ ] Desktop already running: resumes once
- [ ] Another game running: Desktop does not replace it
- [ ] Automatic Desktop setting disabled: no automatic start
- [ ] Cached app list present: automatic start still works
- [ ] No duplicate Game Activity
- [ ] No duplicate virtual-display confirmation

### Resume

- [ ] Home and return
- [ ] Lock and unlock
- [ ] Background and foreground
- [ ] Repeat Home/return multiple times
- [ ] Explicit disconnect prevents resume
- [ ] Quit dialog “No” still permits resume
- [ ] Quit dialog “Yes” prevents resume

### Touchpad

- [ ] Fine movement
- [ ] Large movement
- [ ] Fast direction changes
- [ ] Single click
- [ ] Rapid clicks
- [ ] Double click
- [ ] Double-tap drag
- [ ] Glide
- [ ] New touch interrupts old glide
- [ ] No unexpected stop during long use
- [ ] No stale delayed MouseUp
- [ ] Correct behavior after pointer handoff

### Slide Buttons

- [ ] Tap
- [ ] Hold
- [ ] Slide before hold activation
- [ ] Slide takeover after hold activation
- [ ] Base key releases before slide key press
- [ ] Slide key releases on finger up
- [ ] ACTION_CANCEL releases state
- [ ] Concurrent controls do not incorrectly release shared keys

---

## 12. Update Template

Append major updates using:

```markdown
## YYYY-MM-DD — Change Name

### Files Changed

- path/to/File1.java
- path/to/File2.java

### Original Problem

Describe reproduction and incorrect behavior.

### Implementation

Describe the state machine, algorithm, or architecture.

### Test Results

- Build: Passed / Failed / Pending
- Real device: Passed / Failed / Pending
- Regression: Passed / Pending

### Maintenance Constraints

Record conditions future changes must preserve.
```
