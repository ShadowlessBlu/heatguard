"""Export the three demo shifts for the app's "Play demo shift" button.

The app replays these minute inputs through its own (Kotlin) engine, so the
levels on screen are computed live by the real engine, while the worker's
"true" core temperature comes from the toy model in demo_shift.py and is
shown only for comparison. Re-run after changing demo_shift.py:

    python -m sim.export_demo
"""
from __future__ import annotations

import csv
from pathlib import Path

from sim import demo_shift
from sim.export_golden import fmt

ROOT = Path(__file__).resolve().parents[1]
ASSET = (ROOT.parent / "EchoWear_Android" / "app" / "src" / "main" / "assets"
         / "heat_demo.csv")

SCENARIOS = (
    ("A", "Acclimatized worker ignores the warnings", False, 10),
    ("B", "Acclimatized worker follows the warnings", True, 10),
    ("C", "New worker (day 2) follows the warnings", True, 2),
)

FIELDS = ["scenario", "title", "follows", "days_acclimatized", "age", "t_min", "clock",
          "hr", "enmo_mg", "ta_c", "rh_pct", "fall_event", "working", "ct_true",
          "expected_level"]


def main() -> None:
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with open(ASSET, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(FIELDS)
        for key, title, follows, days in SCENARIOS:
            trace: list = []
            rows = demo_shift.run(follows, days, trace=trace)
            for (p, x, a, _), r in zip(trace, rows):
                w.writerow([fmt(v) for v in (
                    key, title, follows, float(days), p.age, x.t_min, r["clock"],
                    x.hr, x.enmo_mg, x.ta_c, x.rh_pct, x.fall_event, bool(r["working"]),
                    r["ct_true"], a.level.name,
                )])
                n += 1
    print(f"{n} minutes -> {ASSET}")


if __name__ == "__main__":
    main()
