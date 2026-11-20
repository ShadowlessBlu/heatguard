# HeatRisk — EchoWear heat-strain engine (Future Health Challenge 07)

Python reference implementation, plus a Kotlin port in the Android app
(`EchoWear_Android/.../heat/`), where EchoWear's ML already runs. The Python
uses only the standard library.

```
python -m unittest discover -s tests -t .     # 29 tests
python -m sim.demo_shift                      # demo, CSVs in sim/out/
python -m sim.export_golden                   # golden vectors for the Kotlin port
```

## Android port

| File | Role |
|---|---|
| `heat/HeatScience.kt`, `heat/HeatRiskEngine.kt` | Line-for-line port of the Python engine |
| `heat/HeatMonitor.kt` | Per-wearable minute summary of 2 Hz telemetry, plus the engine |
| `heat/SiteConditions.kt` | Site heat: beacon (Day 3), supervisor entry, opt-in Open-Meteo |
| `heat/HeatProfileStore.kt` | Per-worker age, acclimatization, task, clothing (phone only) |
| `ui/screens/HeatScreen.kt` | Heat tab: level and action, reasons, numbers, site, worker |

**Keeping the two versions the same:** change the Python first, run
`python -m sim.export_golden`, then change the Kotlin. `HeatGoldenTest` replays
all 4,770 golden minutes (3 demo shifts and 12 randomized ones) and must match
every level, action, reason and number (to 1e-9). A planted 0.01 °C threshold
change fails it.

```
cd EchoWear_Android
gradlew :app:testDebugUnitTest --offline     # needs JAVA_HOME = Android Studio's jbr
```

How it runs: every minute `MonitorService` closes the minute for each wearer
whose heat monitoring is on. Heart rate is the median of beats from still or
moderately moving packets. Its quality comes from the wearable's motion tiers,
so during heavy work it goes "not measured" rather than trusting
motion-corrupted PPG. A rise to ORANGE or RED is logged as a `HEAT_*` event
(not counted as a fall) and posts a notification with the action only, no
vitals. A fall alert feeds the engine's collapse latch. "I'm OK" or "collapse
handled" clears it.

## What it does

Once a minute, for each worker, it takes heart rate (with the wearable's
motion-quality score), wrist motion, site heat and the fall flag. It returns
**GREEN / YELLOW / ORANGE / RED**, the reasons, and one action.

| Part | Method | Source |
|---|---|---|
| Core temperature from heart rate | ECTemp Kalman filter, 1-min steps | Buller et al. 2013, *Physiol. Meas.* 34:781 |
| Heat strain 0–10 | Physiological Strain Index | Moran et al. 1998, *Am. J. Physiol.* 275:R129 |
| Site heat (WBGT) | BoM simplified WBGT; ISO 7243 outdoor form with black globe; the higher wins | BoM; Stull 2011 wet bulb |
| Safe heat limit | ACGIH TLV (acclimatized) / Action Limit (new), interpolated over 7 days | ACGIH heat-stress TLV |
| Work/rest schedule | Minutes per hour that keep the hourly average metabolic rate under the limit | ACGIH TWA method |
| Workload | Higher of the declared task class and wrist ENMO class | Hildebrand et al. 2014 |
| Stop limits | Est. core temp 38.0 °C (new) → 38.5 °C (acclimatized); 5-min HR > 180 − age | ACGIH |

## Levels

| Level | Triggered by | Action |
|---|---|---|
| GREEN | Heat > 2 °C under limit, strain low | Keep working, drink water |
| YELLOW | Heat near/over limit but a work/rest cycle is safe; core temp rising; PSI ≥ 3 | Drink water + shade, or follow the work/rest schedule |
| ORANGE | No safe work at this heat; core temp near stop limit; PSI ≥ 5 | Stop, rest in shade, cool down |
| RED | Core temp at stop limit; PSI ≥ 7; sustained high HR; fall/collapse | Stop and get help / emergency (collapse in heat) |

Escalation is immediate. De-escalation waits 10 minutes. A collapse stays RED
until acknowledged. Missing data is reported as missing (`None` +
`hr_not_measured` / `env_not_measured`), never as 0 and never as "safe".
`Assessment.supervisor_view()` gives level, action and work minutes only, with
no vitals.

## Demo result (toy physiology, illustrative only)

Same hot morning (06:00–12:30, WBGT 29→34 °C), moderate work:

| Run | Peak true core temp | Minutes ≥ 38.5 °C | First RED | Collapse |
|---|---|---|---|---|
| A. Acclimatized, ignores warnings | 39.5 °C | 200 | 08:28 | 10:18 |
| B. Acclimatized, follows warnings | 37.95 °C | 0 | never | none |
| C. New worker (day 2), follows | 37.94 °C | 0 | never | none |

## Limitations (state these in the submission)

- **The demo physiology is a toy model** written for `sim/demo_shift.py`. It
  shows the logic behaves sensibly, not that it is accurate. In the demo,
  ECTemp reads 0.3–0.5 °C under the toy "true" value while temperature is
  rising. Real validation needs measured core temperature.
- ECTemp was validated in soldiers and athletes (~0.3 °C RMSE), not in Gulf
  outdoor workers. Its accuracy also depends on PPG heart rate during work,
  which the MAX30105 gets wrong under heavy motion. That is why low-quality
  beats are ignored.
- WBGT is estimated, not measured. There is no natural wet-bulb sensor, so the
  engine takes the higher estimate.
- Wrist motion under-reads carrying and static effort, so the declared task
  class sets the floor.
- This is a safety and wellness aid, not a medical device, and not a basis for
  employment decisions.
