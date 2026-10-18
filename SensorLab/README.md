# Sensor Lab

A professional sensor toolkit for Android, written in Kotlin with Jetpack Compose and tuned for the
**Samsung Galaxy S25 Ultra**. It turns every sensor the phone exposes into a usable instrument, and
includes an explorer that lists *all* sensors (including Samsung's vendor-specific ones) with live
values and CSV logging.

- **Language / UI:** Kotlin 2.2, Jetpack Compose (Material 3), single activity
- **Min / target SDK:** Android 12 (API 31) / Android 16 (API 36)
- **Dependencies:** AndroidX only (Compose, Activity, Lifecycle, CameraX, Biometric). No analytics, no network access.

## Instruments

| Tool | Hardware used | What it does |
|---|---|---|
| Compass | Rotation vector (accel + gyro + magnetometer) | Magnetic & true heading (WMM declination), flat or upright mode, bearing lock, field-strength interference warning, calibration prompt |
| Spirit level | Gravity sensor | Bubble (surface) and tube (edge) level, log-scaled dial, inclinometer, slope % and mm/m, persistent calibration, haptic "level" click |
| 3D attitude | Rotation / game / geomagnetic rotation vectors | Live 3D model of the phone you can orbit, quaternion, Euler angles, rotation matrix, heading accuracy |
| GNSS & speed | GNSS receiver | Sky plot by constellation, C/N0 per satellite, **L1 + L5 dual-band detection**, position (DMS, MSL altitude), chipset capabilities, speedometer with trip stats |
| Motion lab | Accelerometer, linear accel, gravity, gyroscope (+ uncalibrated) | 4-trace live charts up to 500 Hz, measured sample rate, peak hold, G-meter, bias/drift readout |
| Seismometer | Accelerometer at max rate | High-passed seismograph, PGA, Modified Mercalli intensity, STA/LTA event trigger |
| Pedometer | Step counter + step detector | Session steps, goal ring, cadence, distance, pace |
| Haptics lab | Vibration motor | Amplitude/duration one-shots, press-and-hold, waveforms, predefined effects, composition primitives with a sequence builder, resonant frequency / Q factor |
| Barometer | Pressure sensor | hPa / inHg / mmHg, 30-min history, trend, altimeter, relative altitude + floor counter, QNH / known-elevation calibration |
| Light meter | Ambient light sensor | Lux, foot-candles, EV₁₀₀, ISO/aperture → shutter calculator, history, extra vendor channels |
| Proximity | Proximity sensor | Near/far state, distance, event log |
| Magnetometer | Magnetometer (+ uncalibrated) | Field strength, components, baseline Δ, audible/haptic metal detector, hard-iron bias |
| Sound meter | Microphones | dB(A)/dB(Z) with IEC fast weighting, Leq/min/max/peak, 60 s history, live FFT spectrum, dominant frequency + tuner, calibration, microphone list |
| Pulse | Rear camera + flash | Fingertip photoplethysmography: waveform, BPM by autocorrelation, signal quality |
| Touch & S Pen | Touch digitizer + S Pen | Multi-touch tracker, pressure-sensitive ink, tilt, orientation, hover distance, side button, contact size, report rate |
| Radios | Wi-Fi, Bluetooth LE, cellular modem, NFC, UWB | Wi-Fi scan (band, channel, width, Wi-Fi 6E/7, security), BLE scan, 5G NR/LTE RSRP/RSRQ/SINR, NFC tag reader with NDEF decoding, UWB presence |
| Battery & thermal | Fuel gauge, thermal sensors | Current, voltage, power, temperature, cycles, thermal status and headroom |
| Device & biometrics | Display, SoC, fingerprint sensor | Display modes & refresh rates, SoC clusters, RAM/storage, hardware feature list, input devices, fingerprint sensor test |
| Sensor explorer | Every sensor | Searchable list of all sensors incl. wake-up and Samsung vendor sensors; per-sensor specs, live values, chart, rate control, one-shot triggers, **CSV export** |

## Building

### Option A — Android Studio (recommended)
1. Install a recent Android Studio (Narwhal or newer).
2. **File › Open** and select this folder. Let Gradle sync (first sync downloads the SDK pieces).
3. Plug in the S25 Ultra with USB debugging on (Settings › About phone › Software information › tap *Build number* 7×, then Developer options › USB debugging).
4. Press **Run**.

### Option B — GitHub Actions (no local setup)
1. Push this folder to a new GitHub repository.
2. The included workflow (`.github/workflows/build.yml`) builds automatically.
3. Open the run under **Actions** and download the `SensorLab-apk` artifact.
4. Copy `app-release.apk` to the phone and open it (allow *Install unknown apps* for your file manager when asked).

### Option C — command line
```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug         # straight onto a connected phone
```
Requires JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties` with `sdk.dir=`).

The release build is signed with the debug key so it installs immediately. Create your own signing
config before distributing it anywhere.

## Permissions

Each permission is requested only when you open the tool that needs it.

| Permission | Used by |
|---|---|
| Location (precise) | GNSS, true-north declination, Wi-Fi scan results |
| Physical activity | Pedometer |
| Microphone | Sound meter (audio is analysed in memory, never recorded) |
| Camera | Pulse (frames are averaged in memory, never stored) |
| Nearby devices | Bluetooth LE and Wi-Fi scanning |
| NFC, Vibrate, Biometric, High sampling rate sensors | Granted automatically |

## Notes and limits

- **UWB** is detected but ranging needs a second UWB device (SmartTag2, car key) and isn't possible standalone.
- **Barometer altitude** needs today's sea-level pressure (QNH) or a known elevation; relative altitude works without it.
- **Sound levels** use Android's microphone-sensitivity spec as the default calibration; adjust against a reference meter for accuracy.
- **Pulse** is a demonstration of the camera as a sensor, not a medical device.
- **Samsung current readings**: some Samsung firmware reports battery current in mA instead of µA; the app normalises this.
- **Wi-Fi scans** are throttled by Android to four per two minutes unless disabled in Developer options.
- The **S25 Ultra has no heart-rate, humidity or ambient-temperature sensor**; those tools are adapted (camera PPG) or reported as absent in the explorer.

## Project layout

```
app/src/main/java/dev/sensorlab/app/
├── MainActivity.kt, AppRoot.kt      single activity + lightweight back-stack navigation
├── home/                            dashboard and tool registry
├── core/                            sensor hooks, ring buffers, catalog, prefs, location
├── ui/                              theme, shared components, live chart, permission gate
└── feature/                         one package per instrument
```
