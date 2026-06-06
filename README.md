# Vitas Tread Metrics

A standalone Android application for displaying and recording live Peloton
Tread speed, incline, distance, and elapsed time over other applications such
as Netflix.

This directory can be copied into a new repository without the original
OpenPelo Flutter project.

## Independence From OpenPelo

The app has **no build-time or runtime dependency on OpenPelo**, Flutter, or
the original desktop application.

It is a normal native Android Gradle project containing:

- Kotlin Android source code.
- A Gradle wrapper.
- macOS command-line setup, build, install, and diagnostic scripts.
- A GitHub Actions APK build workflow.

The package name remains `org.openpelo.beltstatusdump` so an APK built from this
standalone project upgrades the version already installed on the Tread. The
name is only an Android identifier; it does not import or call OpenPelo code.

The app does depend on Peloton firmware at runtime. Real metrics are read
read-only from the exported service:

```text
com.onepeloton.affernetservice.ITreadInterface
```

This interface is present and verified on a `PLTN-TTR01` running Android 10.
A future Peloton firmware update could change or remove it.

## Features

- Real Peloton speed and incline through the Affernet Binder service.
- Calculated distance using speed and monotonic elapsed time.
- Elapsed workout timer.
- Expanded and compact overlays over Netflix and other applications.
- Pause and resume from the overlay.
- Hide and restore the overlay without stopping tracking.
- Local SQLite workout and sample storage.
- CSV export.
- Simulated metrics for development without a Tread.

## Project Layout

```text
peloton-tread-overlay/
├── app/                         Android application
├── gradle/wrapper/              Pinned Gradle wrapper
├── scripts/
│   ├── setup_android_cli_macos.sh
│   ├── build_overlay_apk.sh
│   ├── install_overlay_apk.sh
│   └── collect_peloton_diagnostics.sh
├── .github/workflows/build-apk.yml
├── gradlew
└── README.md
```

## Mac Prerequisites

You do not need Android Studio. The included setup script installs:

- OpenJDK 17.
- Android command-line tools.
- Android platform tools, including ADB.
- Android API 34 and Build Tools 34.

Homebrew must already be installed. Then run from this directory:

```bash
./scripts/setup_android_cli_macos.sh
```

Close and reopen Terminal if a newly installed command is not immediately
available.

## Prepare The Peloton

ADB and third-party APK installation may be unsupported by Peloton. Do not
remove Peloton system packages, and always retain the safety key and Tread Lock.

1. Enable developer options and USB debugging on the Peloton.
2. Connect the Mac to the Tread with a data-capable USB cable.
3. Accept the USB debugging authorization prompt on the Tread.
4. Confirm the connection:

```bash
source scripts/android_env.sh
adb devices -l
```

The device should be listed with status `device`, not `unauthorized`.

## Build

From the project root:

```bash
./scripts/build_overlay_apk.sh
```

This runs unit tests and creates:

```text
dist/VitasTreadMetrics-debug.apk
```

## Install Or Upgrade

With the Tread connected:

```bash
./scripts/install_overlay_apk.sh
```

The script:

1. Builds the APK if necessary.
2. Installs or upgrades it with `adb install -r`.
3. Grants draw-over-apps access through ADB when supported.
4. Launches the application.

The installed app appears in the Android launcher/app drawer as:

```text
Vitas Tread Metrics
```

If it does not appear on the home screen, open the app drawer and optionally
add its icon to the launcher manually.

## Start A Workout

1. Unlock Tread Lock.
2. Open **Vitas Tread Metrics**.
3. Confirm **Use simulated metrics** is off.
4. Keep **Peloton speed unit is mph** selected for the tested Tread.
5. Press **Start**.
6. Open Netflix or another application.
7. Press **Stop + Save** in the app when finished.

The app currently requires a manual start after installation or device reboot.
It does not automatically start tracking.

## Overlay Controls

| Control | Action |
| --- | --- |
| `Ⅱ` | Pause timer and distance accumulation |
| `▶` | Resume timer and distance accumulation |
| `−` | Switch to compact mode |
| `□` | Return to expanded mode |
| `×` | Hide the overlay while tracking continues |

Restore a hidden overlay from the ongoing Android notification or the
**Show / Hide Overlay** button in the application.

## Data And Export

The app saves sessions and one-second metric samples in its private SQLite
database. Use **Export All Sessions As CSV** to choose an export destination.

Distance is calculated from the received speed samples. Peloton's
`BELT_STATUS` broadcast is not used for speed or incline; it contains only an
optional belt-moving boolean.

## Diagnostics

Normal status should show:

```text
Status: TRACKING
Data source: Peloton Affernet
Real metric samples: <increasing number>
```

If metrics do not update, leave tracking active for at least 30 seconds and
run:

```bash
./scripts/collect_peloton_diagnostics.sh
```

The report is written under `diagnostics/`.

## GitHub Build

When this directory becomes the root of a new GitHub repository, the included
workflow builds and tests the debug APK and publishes it as a workflow
artifact:

```text
.github/workflows/build-apk.yml
```

## Current Limitations

- Tested only on the connected `PLTN-TTR01` firmware.
- Peloton may change the internal Affernet service in a future update.
- The APK is debug-signed, not production release-signed.
- Tracking does not automatically resume after reboot.
- Tread Lock cannot and should not be disabled by this app.
