# Movement Tracker

An Android app that uses the phone camera to measure **player speed** and
**ball speed** in real time — point the camera at the action and get live
km/h readouts. Built for the **Google Pixel 8**, but it runs on any
Android 8.0+ (API 26) device; everything is on-device, no internet needed.

Beyond the live readouts it records **sessions** (top/average speed,
distance, sprints, every kick/bowl with its peak speed — all stored only
on your phone), **detects what you're doing** (cricket bowling, soccer
shots, sprinting, vertical jumps) and gives **coaching suggestions
computed from your own numbers**, analyses **240 fps slow-motion clips**
for accurate fast-ball speeds, and can calibrate real-world scale
automatically with **ARCore**.

It also records a **replay video** of every session with jump-to-event
buttons, draws the **ball's flight path** and measures **launch angle**,
tracks a **second player**, listens for the **impact sound** of a strike,
runs **drills** (N attempts vs a target speed, with beeps and a
scorecard), **speaks ball speeds out loud**, charts your **speed
timeline**, keeps **personal bests** with a monthly trend, warns when the
**phone is moving**, and **exports** everything to JSON.

On top of that it generates **share cards** (a polished stats image over
a replay frame, ready for any messaging app), lets you **challenge a
friend** with a paste-able drill code and play against their scorecard,
records **shot placement** against a 3×3 goal target with a per-session
heat grid, plays two replays **side by side** for technique comparison,
measures **technique at the strike moment** (knee angle and
follow-through on shots, elbow angle and release height on bowls) and
coaches from those numbers, walks new users through a **guided setup
check** with live checkmarks, and shows a **calibration confidence
badge** so you always know how much to trust the readings.

## How it works

| What | How |
|---|---|
| Player detection | [ML Kit Pose Detection](https://developers.google.com/ml-kit/vision/pose-detection) finds 33 body landmarks per frame |
| Player speed | The hip midpoint is tracked across frames; displacement over a ~0.35 s sliding window → m/s → km/h |
| Ball detection | ML Kit Object Detection (stream mode, multi-object) with a bundled TFLite classifier whose classes include soccer/cricket/tennis/base/rugby balls — labelled balls are preferred, shape heuristics (small, round-ish) are the fallback, and tracking IDs follow it across frames |
| Ball speed | Same sliding-window math with a short (~0.12 s) window so kick peaks aren't averaged away; the **peak** is kept until you reset it |
| Pixels → metres | **AR (best):** ARCore measures the distance to the playing surface; scale follows from the camera's focal length. **Manual:** tap two points a known distance apart. **Auto:** estimated from the player's apparent height (assumes 1.70 m) |
| Sessions | Start/Stop from the camera screen; speed timeline, distance, sprints and ball events are saved as JSON in private app storage — nothing leaves the device |
| Activity detection | A rolling window of pose landmarks is inspected when the ball speed spikes: wrist above head + fast arm swing → cricket bowl; ankle beside ball + fast leg swing → soccer shot; sustained >14 km/h → sprint |
| Suggestions | Computed from measured ratios (ball-to-approach speed, foot-to-ball energy transfer, best-vs-average delivery gap, acceleration in km/h per second) — every tip quotes your actual numbers |
| Slow-mo analysis | Pick a 120/240 fps clip; frames are timestamped at the true capture rate (1/240 s apart), which is what makes 100+ km/h balls measurable |

The camera analysis stream runs at 1280×720 / ~30 fps with both ML models
on-device — the Pixel 8's Tensor G3 handles this comfortably in real time.

## Building & installing

1. Open the project in **Android Studio** (Hedgehog or newer). If Studio asks
   to add the Gradle wrapper, accept — the wrapper config is included, only
   the bootstrap jar is intentionally not committed.
2. Let Gradle sync (dependencies: CameraX, ML Kit — downloaded automatically).
3. Enable **Developer options → USB debugging** on your Pixel 8, plug it in,
   press **Run**.

## Getting accurate readings

- **Film side-on.** A single camera has no depth perception — speed is
  accurate for motion *across* the frame and underestimated for motion
  toward/away from the camera.
- **Calibrate manually** when you can: two cones 10 m apart beats the
  auto height-based estimate.
- **Keep the phone still** (tripod or lean it against something). The math
  assumes a static camera.
- Good light and a ball that contrasts with the background help the generic
  object detector a lot.
- Expect roughly **5–10 % error** with a good setup; that's on par with
  consumer speed-tracking apps. Very fast balls (>80 km/h) blur at 30 fps —
  see the roadmap.

## Roadmap

- [x] **Session recording** — per-session stats, sprint history, ball events,
  stored on-device.
- [x] **Activity detection + coaching** — cricket bowling / soccer shots /
  sprints classified from pose dynamics, with data-driven suggestions.
- [x] **Slow-motion clip analysis** — 240 fps clips analysed at the true
  capture rate for accurate fast-kick/throw speeds.
- [x] **ARCore scale** — real-world scale from plane detection + camera
  intrinsics instead of manual calibration.
- [x] **Custom ball model** — a bundled TFLite labeler (3.8 MB, in
  `app/src/main/assets/`, from Google's ML Kit samples) classifies every
  detection; candidates labelled soccer/cricket/tennis/base/rugby ball are
  preferred over shape heuristics for much more reliable ball lock-on.
- [x] **Speed timeline chart** in the session detail screen, with ball
  events marked on the baseline.
- [x] **Session replay video** — recorded during live sessions, with
  jump-to-event buttons and the measured speed shown at the playback
  position.
- [x] **Personal bests & trend** — fastest sprint/shot/bowl, highest jump,
  totals, and top-speed change vs the previous month.
- [x] **Drill mode** — N attempts against a target ball speed, beeps per
  attempt, scorecard at the end.
- [x] **Voice announcements** — each shot/bowl speed spoken aloud.
- [x] **Impact sound confirmation** — a mic RMS spike near a ball event
  marks it "impact heard" (optional permission).
- [x] **Vertical jump detection** — jump height from the hip trajectory.
- [x] **Second player tracking** — box-centre speed for another detected
  person, outlined on the overlay.
- [x] **Ball flight path + launch angle** — fading trail on the overlay,
  angle attached to the matching event.
- [x] **Shake warning** when the gyroscope says the phone isn't still.
- [x] **JSON export** of all sessions from the sessions screen.
- [x] **Your real height** for auto calibration.
- [x] **Share cards** — a 4:5 stats card rendered over a replay frame from
  the session's best moment, shared via the system share sheet.
- [x] **Challenge a friend** — a finished drill exports a short code;
  pasting it into the drill dialog replays the same drill against the
  sender's scorecard, with a win/lose verdict.
- [x] **Shot placement zones** — tap the goal's corners to set a 3×3
  target; every attempt is tagged with its zone (or "off target"), shown
  as a heat grid with an accuracy percentage in the session detail.
- [x] **Side-by-side comparison** — two session replays stacked, each
  cued to an event, played together for technique comparison.
- [x] **Technique feedback** — knee angle at contact and follow-through
  height for shots, elbow angle and release height for bowls, measured
  from the pose skeleton at the strike moment and fed into the coaching
  suggestions.
- [x] **Guided setup wizard** — a live checklist (phone still, player
  fully in frame, scale calibrated) on first launch and behind a Setup
  button.
- [x] **Calibration confidence badge** — colour-coded status with an
  error band (AR/manual ≈ ±5 %, auto ≈ ±15 %).
- [ ] Multi-pose skeletons (two full skeletons needs a different pose model,
  e.g. MoveNet MultiPose).

## Project layout

```
app/src/main/java/com/movementtracker/
├── MainActivity.kt              # CameraX setup, wires analysis → UI
├── analysis/
│   ├── FrameAnalyzer.kt         # Runs pose + labelled object detection per frame
│   ├── SpeedCalculator.kt       # Sliding-window speed with smoothing
│   ├── CalibrationManager.kt    # AR / manual / auto px→m scale
│   ├── BallTracker.kt           # Picks & follows the ball (label-first)
│   ├── PersonTracker.kt         # Follows a second detected player
│   ├── JumpDetector.kt          # Vertical jumps from hip trajectory
│   ├── ImpactAudioDetector.kt   # Ball-strike sound spikes from the mic
│   ├── ActivityClassifier.kt    # Bowl vs shot vs generic ball event
│   └── SlowMoAnalyzer.kt        # Offline high-fps clip pipeline
├── ar/
│   ├── ArCalibrateActivity.kt   # ARCore distance measurement screen
│   └── CameraBackgroundRenderer.kt
├── session/
│   ├── SessionModels.kt         # SessionRecord / events / samples (+JSON)
│   ├── SessionStore.kt          # On-device JSON + replay video storage
│   ├── SessionRecorder.kt       # Live accumulation: distance, sprints…
│   ├── BallEpisodeDetector.kt   # Groups ball-speed spikes into events
│   ├── DrillTracker.kt          # Attempts vs a target speed (or a rival)
│   ├── ChallengeCodec.kt        # Drill scorecard ↔ paste-able code
│   ├── ProgressStats.kt         # Personal bests + monthly trend
│   └── SuggestionEngine.kt      # Coaching advice from measured data
└── ui/
    ├── OverlayView.kt           # Skeleton/ball/trail/target overlay + taps
    ├── SpeedChartView.kt        # Canvas speed-timeline chart
    ├── PlacementGridView.kt     # 3×3 shot-placement heat grid
    ├── ShareCardRenderer.kt     # Session stats → share-card bitmap
    ├── SessionsActivity.kt      # History list, bests, export, compare
    ├── SessionDetailActivity.kt # Stats, chart, placement, suggestions
    ├── ReplayActivity.kt        # Session video with event jumping
    ├── CompareActivity.kt       # Two replays side by side
    └── SlowMoActivity.kt        # Slow-motion clip analysis
```
