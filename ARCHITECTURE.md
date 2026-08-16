# ARCHITECTURE.md

# Moonlight / Artemis Android Custom Architecture

## 1. Scope

This document describes the custom areas that are most relevant to ongoing development.

The actual source code remains authoritative.

---

## 2. High-Level Runtime Flow

Typical automatic streaming path:

```text
App launch
→ PcView
→ choose paired and online PC
→ AppView
→ receive app list and server information
→ decide whether to start/resume Desktop
→ Game
→ NvConnection streaming session
```

Main lifecycle responsibilities:

| Component | Responsibility |
|---|---|
| `PcView` | PC discovery, selection, pairing state, entry into app list |
| `AppView` | App list, current running app, Desktop start/resume coordination |
| `Game` | Active streaming Activity, input routing, orientation, session lifecycle |
| `StreamRecoveryStore` | Durable, same-host recovery admission, candidate evidence, and launch guard |
| `ComputerManagerService` | Normal host polling plus fresh-serverinfo availability evidence |
| `NvConnection` | Streaming and remote input transport |

---

## 3. Automatic Desktop Streaming

### 3.1 Inputs to the Decision

The Desktop launch decision depends on:

- User preference for automatic Desktop streaming
- Whether valid server information has arrived
- Current `runningGameId`
- Available app list
- Presence of `Desktop` / `桌面`
- `Game.terminatedByUser`
- Whether a launch request is already pending
- Virtual-display preference and readiness

### 3.2 Required State Machine

Conceptual states:

```text
WAITING_FOR_SERVER_INFO
WAITING_FOR_APP_LIST
READY_TO_DECIDE
LAUNCH_PENDING
DESKTOP_RUNNING
OTHER_APP_RUNNING
SUPPRESSED_BY_USER
```

Required decision rules:

```text
no server info
→ wait

no app list / Desktop missing
→ wait

user disabled automatic Desktop
→ do nothing

user explicitly terminated
→ do not resume

another app running
→ do not launch Desktop

Desktop running
→ resume once

nothing running
→ start Desktop once
```

Automatic start and automatic resume should not be maintained as two independent launch pipelines. They should converge on one coordinator.

### 3.3 Duplicate-Launch Protection

A pending flag or equivalent state must prevent multiple calls to:

```java
ServerHelper.doStart(...)
```

The pending state must be cleared at the correct lifecycle points.

---

## 4. Streaming Activity Lifecycle

`Game.java` owns the active stream Activity.

Important lifecycle concerns:

- Home
- Lock screen
- App backgrounding
- Activity recreation
- User disconnect
- Confirmed quit
- Quit dialog cancellation
- Automatic resume

`Game.terminatedByUser` means:

```text
true  = explicit user termination; suppress automatic resume
false = lifecycle interruption may be resumed
```

Do not set it merely because a quit dialog was opened.

### 4.1 Established-Stream Host Interruption Recovery

Recovery applies to an established stream interrupted by the host or streaming service. It
is deliberately broader than reboot recovery and includes shutdown, reboot,
power-loss-like disconnect, Sunshine exit, and Sunshine restart.

Admission begins in `Game` only after `connectionStarted()` and only for a non-user
termination. Explicit disconnect, Back, and confirmed quit cancel recovery state.

Two admission paths exist:

```text
established stream + error -1
→ create pending recovery directly

established stream + graceful error 0
→ create same-host provisional candidate
→ require confirmed OFFLINE
   or fresh serverinfo failure followed by later fresh success
→ atomically promote candidate to pending recovery
```

The graceful candidate has an approximately 60-second TTL. `UNKNOWN` does not count as
service loss. The fresh failure/success callbacks are recovery evidence only; they do not
reduce `ComputerManagerService`'s normal three-poll OFFLINE threshold or publish an early
global OFFLINE state. Sunshine fast Restart may never reach final OFFLINE, so the ordered
fresh-serverinfo failure and recovery sequence is required as an independent proof path.

Candidate host UUID, target identity, virtual-display choice, creation time, and optional
service-loss observation are persisted together. All store transitions are serialized.
Promotion creates the recovery session and removes all candidate keys in one synchronous
commit, preventing duplicate callback promotion and cross-candidate evidence leakage.

Once pending, `AppView` requires fresh server information and a newly successful app-list
snapshot. It re-resolves the target by UUID/ID/name, resumes the same target if already
running, starts it when nothing is running, and refuses to replace a different running
application. Persisted attempt admission makes the recovery launch single-flight before
`ServerHelper.doStart(...)` is called.

The recovery Game enters a 30-second `CONNECTED_GUARD` at its first
`connectionStarted()` callback:

```text
recovery Game + guard active + termination 0/-1/other
→ B02 fatal
→ clear matching recovery/candidate state
→ no second candidate, promotion, or recovery launch
```

After 30 seconds of stable connection, the stored recovery record is retired. A later
established-stream interruption is a new independent recovery session even though the
Activity was originally launched as recovery.

Safety invariant: graceful `0` plus continuously available Sunshine must not be promoted
and must not automatically reconnect. A live candidate blocks ordinary auto-Desktop. If an
unconsumed candidate reaches its natural TTL, the Store atomically removes the candidate
keys and writes a persistent same-host ordinary-auto suppression tombstone in one
synchronous commit under the same lock. This applies even if service loss was observed but
promotion never completed.

Ordinary-auto admission is therefore:

```text
matching live candidate OR matching expiry tombstone
→ suppress AppView auto-resume/auto-start and PcView auto-entry for that host
```

AppView checks this admission both before deciding and immediately before dispatching Game.
Explicit App launch clears the matching tombstone. A new ordinary Game, new graceful
candidate, direct `-1` recovery, or successful OFFLINE/transient promotion also clears or
replaces it. Tombstone keys are derived from normalized host UUIDs, so another host remains
independent and the suppression cannot become a permanent global auto-Desktop disable.

---

## 5. Touch Input Architecture

### 5.1 TrackpadContext

`TrackpadContext` converts relative touch displacement into remote mouse events.

Main stages:

```text
touchDownEvent
→ initialize gesture state
→ start smoothing ticker

touchMoveEvent
→ update targetAccumX/Y

tick
→ update second-order motion state
→ round movement to integer deltas
→ send mouse movement
→ preserve sub-pixel carry-over

touchUpEvent
→ click / drag release / inertial glide
```

Important state:

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
gliding
isTouching
tickerFuture
```

### 5.2 Motion Model

The current model is a second-order damped follower.

Conceptual equation:

```text
acceleration =
position_error × natural_frequency²
− velocity × 2 × damping_ratio × natural_frequency
```

Current intended damping ratio:

```text
1.0
```

### 5.3 Integer Mouse Transport

Remote mouse movement is sent as integer deltas.

Sub-integer residual must be preserved in:

```java
carryOverX
carryOverY
```

### 5.4 Scheduled Work

A shared scheduled executor is used for:

- Smoothing ticks
- Delayed MouseUp

This creates important concurrency concerns:

- Old ticker canceling a new ticker
- Delayed MouseUp crossing into a new gesture
- Cleanup during cancellation and disconnect
- Thread-safe access to movement state

---

## 6. RelativeTouchContext

`RelativeTouchContext` extends `TrackpadContext`.

Responsibility:

```text
map Android view coordinates into the effective streamed-video coordinate scale
```

Coordinate consistency requirement:

```text
down, move, and up must use the same scale
```

Scaling inputs include:

- Video dimensions
- View dimensions
- Video scale mode

Potential lifecycle hazard:

- View width or height may be zero before layout.
- Cached scale may become stale after orientation or layout changes.

---

## 7. Pointer Routing

`Game.java` routes Android MotionEvents into touch contexts.

Review points:

- Which context owns pointer index 0
- Which context owns pointer index 1
- What happens when the primary pointer lifts
- Whether the remaining pointer receives a new origin
- Whether two contexts can simultaneously send mouse movement
- Whether pointer count changes affect click logic

Multi-pointer behavior should be explicitly designed before modification.

---

## 8. Virtual Controller Architecture

### 8.1 VirtualController

`VirtualController` owns aggregate controller state, including:

- Button bit mask
- Left and right sticks
- Left and right triggers
- On-screen elements
- Remote controller packet transmission

### 8.2 Standard Risk

A simple shared bit mask is unsafe when multiple controls map to the same bit:

```text
source A presses bit
source B presses same bit
source B releases bit
→ bit may clear while source A still holds it
```

Long-term solutions:

- Per-element input masks
- Per-button reference counts
- Source-ID ownership map
- Central reducer that aggregates all active sources

### 8.3 SlideButton

Vertical composite control:

- Base tap
- Base hold
- Slide up
- Slide down
- Hold-to-slide takeover

Takeover transition:

```text
BASE_HELD
→ release base
→ SLIDE_HELD
→ release slide on ACTION_UP / ACTION_CANCEL
```

### 8.4 SlideButtonLR

Horizontal/up composite control:

- Base tap
- Base hold
- Slide left
- Slide right
- Slide up
- Hold-to-slide takeover

The same takeover ordering applies.

### 8.5 Controller Teardown

Hiding or removing controls must not leave remote input active.

A future centralized reset should clear:

```text
button mask
sticks
triggers
scheduled callbacks
per-element pressed state
```

and send one zeroed controller packet.

---

## 9. Preferences

Main preference model:

```text
app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java
```

Resources:

```text
app/src/main/res/xml/preferences.xml
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
```

Performance concern:

- Avoid reconstructing the full preferences object during every draw or high-frequency input event.
- Cache frequently used visual/input flags where appropriate.
- Refresh cached values when settings change.

---

## 10. Orientation and Video Resolution

Orientation behavior is controlled at runtime, not only by the Manifest.

Relevant areas include:

- `Game.java`
- Requested screen orientation
- Rotation menu behavior
- Configuration changes
- Decoder resolution inversion
- Video scaling
- Relative touch scaling
- External display behavior

Always-forced portrait is a cross-cutting feature, not a one-line Manifest change.

---

## 11. Build Structure

Primary command:

```powershell
.\gradlew.bat :app:assembleNonRoot_gameDebug
```

Relevant project areas:

```text
app/
gradle/
gradlew
gradlew.bat
settings.gradle
build.gradle
app/build.gradle
```

Build verification should include:

```powershell
git diff --check
.\gradlew.bat :app:assembleNonRoot_gameDebug
```

---

## 12. Critical Invariants

Future changes must preserve:

1. User disconnect suppresses automatic resume.
2. Quit-dialog cancellation does not suppress resume.
3. Another running game is not replaced by Desktop.
4. Desktop start/resume is not requested twice.
5. Touch down, move, and up use one coordinate system.
6. Carry-over is calculated after integer delta accounting.
7. Long-press slide takeover releases the base key first.
8. No input remains stuck after ACTION_CANCEL or teardown.
9. Touchpad remains relative, not joystick-style.
10. Rejected first-order alpha smoothing is not reintroduced accidentally.
