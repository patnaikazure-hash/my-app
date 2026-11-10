# Rail Rush

An endless 3-lane runner for Android, written in Kotlin with no game engine and no external assets.
All graphics are drawn with `Canvas` in a pseudo-3D perspective, and the sound effects are synthesized in code.

Run along the rail yard at sunset, dodge trains, jump barriers, roll under signs, and grab coins and power-ups.
The game gets faster the longer you survive.

## Controls

| Gesture | Action |
|---|---|
| Swipe left / right | Switch lanes |
| Swipe up | Jump (low red/white barriers) |
| Swipe down | Roll (high yellow/black barriers). While in the air, it slams you back to the ground |
| Tap the pause button | Pause |

Keyboard (emulator or Chromebook): arrows or WASD, Space to jump, Enter to start, P to pause.

**Rules**
- Hitting anything head-on ends the run.
- Bumping into the side of a train knocks you back. Two bumps within a few seconds ends the run.
- Power-ups: **M** magnet pulls in nearby coins, **S** shield absorbs one crash, **x2** doubles your score.
- Your best score and total coins are saved on the device.

## Building

Requirements: Android Studio (Ladybug or newer) or the Android SDK with JDK 17.

```bash
./gradlew assembleDebug
```

The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected phone:

```bash
./gradlew installDebug
```

You can also open the project in Android Studio and press **Run**.

### Without installing anything

Every push runs the **Build APK** GitHub Actions workflow. Open the repository's **Actions** tab, pick the
latest run, and download the `rail-rush-apk` artifact. Copy the APK to your phone and open it
(allow "install unknown apps" when asked).

## Tests

```bash
./gradlew testDebugUnitTest
```

The game rules live in `World.kt` and have no Android dependencies. The tests cover jumping, rolling,
collisions, stumbling, shields, coins and the magnet. One test generates long tracks for 80 random seeds
and proves that the generator never builds an impossible situation, meaning a reachable free lane always exists.

## Code layout

| File | Purpose |
|---|---|
| `World.kt` | Game rules: physics, collisions, scoring, power-ups, procedural track generation |
| `Renderer.kt` | Perspective camera and all drawing: sky, city, tracks, trains, barriers, runner, HUD, menus |
| `GameView.kt` | Frame loop (`Choreographer`), swipe/keyboard input, saving best score |
| `Sfx.kt` | Generates sound effects as WAV files and plays them with `SoundPool` |
| `MainActivity.kt` | Full-screen immersive activity |
