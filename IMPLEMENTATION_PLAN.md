# Vitas Tread Metrics Roadmap

## Current State

Version `0.4.3` is a standalone native Android application with:

- Real speed and incline from Peloton's Affernet Binder service.
- Foreground tracking and a draw-over-apps overlay.
- Compact, expanded, pause/resume, hide, and restore controls.
- Distance integration using monotonic elapsed time.
- SQLite session/sample persistence and CSV export.
- Gradle wrapper, macOS CLI scripts, and GitHub Actions.

The APK has been built, installed, and validated on a physical `PLTN-TTR01`
running Android 10.

## Runtime Architecture

```text
Peloton Affernet service
        |
        | read-only Binder polling
        v
AffernetTreadClient
        |
        v
TrackingService
   |        |         |
   |        |         +--> SQLite sessions and samples
   |        +------------> distance and elapsed-time calculation
   +---------------------> floating overlay and app UI
```

The app does not require OpenPelo. OpenPelo may optionally install or launch the
APK, but that is a convenience integration outside this project.

## Next Priorities

### 1. Reliability

- Automatically rebind if the Affernet service restarts.
- Recover or explicitly close an interrupted session after process death.
- Validate multi-hour sessions and distance accuracy.
- Detect unsupported Peloton models and firmware clearly.

### 2. Distribution

- Add a production signing configuration using secrets outside Git.
- Build signed release APKs in GitHub Actions.
- Publish versioned APKs and checksums in GitHub Releases.
- Add upgrade instructions that preserve the existing application data.

### 3. User Experience

- Add an optional setting to start in compact mode.
- Persist overlay position.
- Add adjustable text size and opacity.
- Add a direct stop-and-save overlay action if it can be made hard to trigger
  accidentally.

### 4. Testing

- Add Android instrumentation tests for service and overlay lifecycle.
- Add a fake Binder implementation for Affernet integration tests.
- Test pause/resume and distance behavior across process recreation.
- Maintain a physical-device smoke-test checklist.

## Optional Features

- Automatic start after reboot should remain opt-in.
- A home-screen widget may show the latest session, but it will not replace the
  overlay while Netflix or another app is foregrounded.
- OpenPelo integration can consume a published APK, but must remain optional so
  this repository stays independently buildable and installable.
