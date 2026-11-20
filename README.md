# HeatGuard

**A wristband and phone app that warn outdoor workers and their supervisors about heat strain before someone collapses.**

Built for the Future Health Digital Innovation Challenge, *Challenge 07: Climate-Resilient Worker Health*.

Every minute, for every worker, HeatGuard combines site heat (WBGT), the task, clothing, how many days the worker has been in this heat, and, with consent, the worker's heart rate. It gives one clear level and one action:

| Level | What the worker sees |
|---|---|
| 🟢 **Green** | Keep working. Drink water every 15-20 minutes. |
| 🟡 **Yellow** | Drink water and take shade, or: *each hour, work 20 min, rest 40 min in shade* |
| 🟠 **Orange** | Stop work. Rest in shade, drink water, loosen clothing. |
| 🔴 **Red** | Stop now and tell your supervisor, or emergency for a collapse during heat strain |

In a simulated hot morning, a worker who ignored the warnings got the first **Red 1 h 50 min before collapsing**. The same worker following the warnings never went above 38 °C core temperature. These are simulated results; see the [limitations](docs/TECHNICAL.md#7-limitations).

## What's here

| Folder | Contents |
|---|---|
| [`HeatRisk/`](HeatRisk/) | Python reference version of the heat-risk engine, tests (29), simulated shifts, golden test vectors |
| [`EchoWear_Android/`](EchoWear_Android/) | Android app (Kotlin, Jetpack Compose): Heat screen, per-worker engine, site conditions, alerts, encrypted storage, demo replay |
| [`EchoWear_Headless/`](EchoWear_Headless/) | Firmware for the screenless band (ESP32-S3) |
| [`EchoWear_TouchLCD_Unified/`](EchoWear_TouchLCD_Unified/) | Firmware for the touch-screen band |
| [`Model/`](Model/) | Fall-detection model (Keras, float32 and INT8 TFLite) |
| [`docs/TECHNICAL.md`](docs/TECHNICAL.md) | **Technical documentation:** architecture, method, data assumptions, clinical boundaries, test results, limitations |

HeatGuard builds on EchoWear, an existing fall-detection wearable, so folder and package names still say `EchoWear`.

## Quick start

```bash
cd HeatRisk
python -m unittest discover -s tests -t .
python -m sim.demo_shift
```

```bash
cd EchoWear_Android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

In the app, open the **Heat** screen and press **Play demo shift**. It replays a simulated hot shift through the real engine, so you can see the levels without a band.

## Not a medical device

HeatGuard is a safety and wellness aid. Core temperature is *estimated* from heart rate and has not yet been validated in outdoor workers. Read [Clinical boundaries](docs/TECHNICAL.md#4-clinical-boundaries) and [Limitations](docs/TECHNICAL.md#7-limitations) before relying on it.
