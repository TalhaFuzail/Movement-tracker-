# Movement Tracker

An Android app that uses the phone camera to measure **player speed** and
**ball speed** in real time — point the camera at the action and get live
km/h readouts. Built for the **Google Pixel 8**, but it runs on any
Android 8.0+ (API 26) device; everything is on-device, no internet needed.

Beyond the live readouts it records **sessions** (top/average speed,
distance, sprints, every kick/bowl with its peak speed — all stored only
on your phone), **detects what you're doing** (cricket bowling, soccer
shots, sprinting) and gives **coaching suggestions computed from your own
numbers**, analyses **240 fps slow-motion clips** for accurate fast-ball
speeds, and can calibrate real-world scale automatically with **ARCore**.

## How it works

| What | How |
|---|---|
| Player detection | [ML Kit Pose Detection](https://developers.google.com/ml-kit/vision/pose-detection) finds 33 body landmarks per frame |
| Player speed | The hip midpoint is tracked across frames; displacement over a ~0.35 s sliding window → m/s → km/h |
| Ball detection | ML Kit Object Detection (stream mode, multi-object) + a heuristic that picks the small, round-ish detection and follows its tracking ID |
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
- [ ] **Custom ball model** — replace the generic object detector with a
  small TFLite model trained on sports balls (ML Kit supports custom models
  drop-in) for much more reliable ball lock-on.
- [ ] Multi-player tracking (pose detection currently follows one person).
- [ ] Speed timeline chart in the session detail screen.

## Project layout

```
app/src/main/java/com/movementtracker/
├── MainActivity.kt              # CameraX setup, wires analysis → UI
├── analysis/
│   ├── FrameAnalyzer.kt         # Runs pose + object detection per frame
│   ├── SpeedCalculator.kt       # Sliding-window speed with smoothing
│   ├── CalibrationManager.kt    # AR / manual / auto px→m scale
│   ├── BallTracker.kt           # Picks & follows the ball across frames
│   ├── ActivityClassifier.kt    # Bowl vs shot vs generic ball event
│   └── SlowMoAnalyzer.kt        # Offline high-fps clip pipeline
├── ar/
│   ├── ArCalibrateActivity.kt   # ARCore distance measurement screen
│   └── CameraBackgroundRenderer.kt
├── session/
│   ├── SessionModels.kt         # SessionRecord / events / samples (+JSON)
│   ├── SessionStore.kt          # On-device JSON persistence
│   ├── SessionRecorder.kt       # Live accumulation: distance, sprints…
│   ├── BallEpisodeDetector.kt   # Groups ball-speed spikes into events
│   └── SuggestionEngine.kt      # Coaching advice from measured data
└── ui/
    ├── OverlayView.kt           # Skeleton/ball overlay + calibration taps
    ├── SessionsActivity.kt      # Session history list
    ├── SessionDetailActivity.kt # Stats, events, suggestions
    └── SlowMoActivity.kt        # Slow-motion clip analysis
```
