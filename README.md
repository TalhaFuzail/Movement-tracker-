# Movement Tracker

An Android app that uses the phone camera to measure **player speed** and
**ball speed** in real time — point the camera at the action and get live
km/h readouts. Built for and tested against the **Google Pixel 8**, but it
runs on any Android 8.0+ (API 26) device; everything is on-device, no
internet needed.

## How it works

| What | How |
|---|---|
| Player detection | [ML Kit Pose Detection](https://developers.google.com/ml-kit/vision/pose-detection) finds 33 body landmarks per frame |
| Player speed | The hip midpoint is tracked across frames; displacement over a ~0.35 s sliding window → m/s → km/h |
| Ball detection | ML Kit Object Detection (stream mode, multi-object) + a heuristic that picks the small, round-ish detection and follows its tracking ID |
| Ball speed | Same sliding-window math with a short (~0.12 s) window so kick peaks aren't averaged away; the **peak** is kept until you reset it |
| Pixels → metres | **Auto:** while a full body is visible, scale is estimated from the player's apparent height (assumes 1.70 m). **Manual (more accurate):** tap *Calibrate*, tap two on-screen points a known distance apart (cones, goal posts), type the distance |

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

- [ ] **Custom ball model** — replace the generic object detector with a
  small TFLite model trained on sports balls (ML Kit supports custom models
  drop-in) for much more reliable ball lock-on.
- [ ] **Slow-motion clip analysis** — record 240 fps slow-mo on the Pixel 8
  and analyse the clip offline for accurate fast-kick/throw speeds.
- [ ] **ARCore scale** — derive real-world scale from the phone's sensors
  instead of manual calibration.
- [ ] Session recording: per-sprint stats, top speeds, history.
- [ ] Multi-player tracking (pose detection currently follows one person).

## Project layout

```
app/src/main/java/com/movementtracker/
├── MainActivity.kt              # CameraX setup, wires analysis → UI
├── analysis/
│   ├── FrameAnalyzer.kt         # Runs pose + object detection per frame
│   ├── SpeedCalculator.kt       # Sliding-window speed with smoothing
│   ├── CalibrationManager.kt    # Auto (player height) & manual px→m scale
│   └── BallTracker.kt           # Picks & follows the ball across frames
└── ui/
    └── OverlayView.kt           # Skeleton/ball overlay + calibration taps
```
