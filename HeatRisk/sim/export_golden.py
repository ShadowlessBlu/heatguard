"""Export golden vectors: engine inputs and expected outputs, minute by minute.

The Kotlin port in the Android app replays this file and must reproduce every
level, action, reason and number. Re-run after any change to the Python engine:

    python -m sim.export_golden

Writes golden/heat_golden.csv and copies it into the Android unit-test resources.
"""
from __future__ import annotations

import csv
import random
import shutil
from pathlib import Path

from heatrisk import HeatRiskEngine, MinuteInput, WorkerProfile, WorkClass
from heatrisk.workload import CLOTHING_CAV
from sim import demo_shift

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "golden" / "heat_golden.csv"
ANDROID = (ROOT.parent / "EchoWear_Android" / "app" / "src" / "test" / "resources"
           / "heat_golden.csv")

FIELDS = [
    # scenario + profile
    "scenario", "age", "resting_hr", "days_acclimatized", "clothing", "hood", "declared_work",
    # minute input
    "ack_before", "t_min", "hr", "hr_quality", "enmo_mg", "ta_c", "rh_pct", "tg_c", "wbgt_c",
    "fall_event",
    # expected output
    "level", "action", "reasons", "work_min_per_hour", "ct_est", "ct_sd", "psi", "out_hr",
    "wbgt_eff", "wbgt_limit", "work_class",
]


def fmt(v) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, float):
        return repr(v)               # shortest round-trip form
    return str(v)


def fuzz(seed: int, minutes: int = 300):
    """Random but plausible shifts that exercise every branch."""
    rng = random.Random(seed)
    profile = WorkerProfile(
        age=rng.choice([None, 22, 35, 51]),
        resting_hr=rng.choice([None, 58.0, 72.0]),
        days_acclimatized=rng.choice([0, 2, 5, 10]),
        clothing=rng.choice(list(CLOTHING_CAV)),
        hood=rng.random() < 0.2,
        declared_work=rng.choice(list(WorkClass)[:4]),
    )
    eng = HeatRiskEngine(profile)
    hr, ta, rh = rng.uniform(65, 90), rng.uniform(22, 38), rng.uniform(15, 80)
    trace = []
    gap = 0
    for t in range(minutes):
        hr = min(195.0, max(45.0, hr + rng.gauss(0.15, 3.0)))
        ta = min(48.0, max(15.0, ta + rng.gauss(0.01, 0.3)))
        rh = min(95.0, max(5.0, rh + rng.gauss(0, 1.0)))
        if gap == 0 and rng.random() < 0.03:
            gap = rng.randint(3, 15)          # sensor off-wrist / link drop
        env_mode = rng.random()
        x = MinuteInput(
            t_min=t,
            hr=None if gap else hr,
            hr_quality=rng.choice([1.0, 1.0, 1.0, 0.6, 0.3]),
            enmo_mg=None if rng.random() < 0.1 else rng.uniform(0, 650),
            ta_c=None if env_mode < 0.05 else ta,
            rh_pct=None if env_mode < 0.05 else rh,
            tg_c=ta + rng.uniform(0, 20) if rng.random() < 0.4 else None,
            wbgt_c=rng.uniform(20, 36) if 0.05 <= env_mode < 0.12 else None,
            fall_event=rng.random() < 0.01,
        )
        gap = max(0, gap - 1)
        ack = rng.random() < 0.02
        if ack:
            eng.acknowledge()
        trace.append((profile, x, eng.update(x), ack))
    return trace


def main() -> None:
    scenarios: list[tuple[str, list]] = []
    for name, follow, days in (("demo_A", False, 10), ("demo_B", True, 10), ("demo_C", True, 2)):
        tr: list = []
        demo_shift.run(follow, days, trace=tr)
        scenarios.append((name, tr))
    for seed in range(12):
        scenarios.append((f"fuzz_{seed}", fuzz(seed)))

    OUT.parent.mkdir(exist_ok=True)
    n = 0
    with open(OUT, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(FIELDS)
        for name, trace in scenarios:
            for p, x, a, ack in trace:
                w.writerow([fmt(v) for v in (
                    name, p.age, p.resting_hr, float(p.days_acclimatized), p.clothing, p.hood,
                    p.declared_work.name,
                    ack, x.t_min, x.hr, x.hr_quality, x.enmo_mg, x.ta_c, x.rh_pct, x.tg_c,
                    x.wbgt_c, x.fall_event,
                    a.level.name, a.action, ";".join(a.reasons), a.work_min_per_hour,
                    a.ct_est, a.ct_sd, a.psi, a.hr, a.wbgt_eff, a.wbgt_limit, a.work_class.name,
                )])
                n += 1
    ANDROID.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(OUT, ANDROID)
    print(f"{n} minutes across {len(scenarios)} scenarios -> {OUT}\ncopied to {ANDROID}")


if __name__ == "__main__":
    main()
