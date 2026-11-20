"""Demo: one hot morning shift, three runs.

  A. an acclimatized worker ignores every warning and works continuously;
  B. the same worker follows the engine (paces work on YELLOW, rests in
     shade on ORANGE, stops on RED);
  C. a new worker on day 2 follows the engine — stricter limits apply.

ILLUSTRATIVE ONLY. The worker's "true" core temperature and heart rate come
from a deliberately simple toy heat-balance model written for this demo, not
a validated thermoregulation model. It is independent of ECTemp's equations
(so the engine is not just reading back its own model), but it proves only
that the logic behaves sensibly — not that it is accurate. The proper
scenario simulator is Day 3-4 of the plan.

Run from HeatRisk/:  python -m sim.demo_shift
"""
from __future__ import annotations

import csv
import math
import random
from pathlib import Path

from heatrisk import HeatRiskEngine, Level, MinuteInput, WorkerProfile, WorkClass
from heatrisk.environment import wbgt_estimate
from heatrisk.workload import METABOLIC_W, enmo_mg

SHIFT_START_H = 6.0          # 06:00
SHIFT_MIN = 390              # to 12:30, when the UAE summer midday work ban starts
SHADE_WBGT_DROP = 3.0        # °C, shade removes most of the radiant load


def weather(t_min: int) -> tuple[float, float]:
    """Hot, dry-season morning in the Gulf: 29 → 39 °C, 55 → 25 % RH."""
    f = t_min / SHIFT_MIN
    ta = 29.0 + 10.0 * math.sin(f * math.pi / 2)
    rh = 55.0 - 30.0 * f
    return round(ta, 1), round(rh, 1)


class ToyWorker:
    """Toy heat balance. Gains from work and heat; losses toward 36.9 °C."""

    def __init__(self, rng: random.Random):
        self.ct = 37.0
        self.rng = rng

    def step(self, wbgt: float, working: bool, in_shade: bool) -> tuple[float, float]:
        m = METABOLIC_W[WorkClass.MODERATE] if working else METABOLIC_W[WorkClass.REST]
        w = wbgt - (SHADE_WBGT_DROP if in_shade else 0.0)
        gain = 0.0025 * m / 100 + 0.003 * max(0.0, w - 25.0)
        loss = (0.020 if in_shade else 0.012) * (self.ct - 36.9)
        self.ct += gain - loss
        hr = 65 + 0.12 * (m - 115) + 33 * (self.ct - 37.0) + self.rng.gauss(0, 4)
        return self.ct, hr

    def wrist_motion(self, working: bool) -> float:
        n = 100
        if working:
            s = [(self.rng.gauss(0, .15), self.rng.gauss(0, .15), 1 + self.rng.gauss(0, .2)) for _ in range(n)]
        else:
            s = [(self.rng.gauss(0, .01), self.rng.gauss(0, .01), 1 + self.rng.gauss(0, .01)) for _ in range(n)]
        return enmo_mg(s)


def run(follow: bool, days_acclimatized: float, seed: int = 7,
        trace: list | None = None):
    rng = random.Random(seed)
    worker = ToyWorker(rng)
    engine = HeatRiskEngine(WorkerProfile(age=28, days_acclimatized=days_acclimatized,
                                          declared_work=WorkClass.MODERATE))
    rows, level, work_min = [], Level.GREEN, 60
    stopped = collapsed = False
    for t in range(SHIFT_MIN):
        ta, rh = weather(t)
        # What the worker does this minute.
        if not follow:
            working, shade = not collapsed, collapsed
        elif stopped or level >= Level.ORANGE:
            working, shade = False, True
        elif level == Level.YELLOW and work_min is not None and work_min < 60:
            working = (t % 60) < work_min
            shade = not working
        else:
            working, shade = True, False

        wbgt = wbgt_estimate(ta, rh)
        ct_true, hr = worker.step(wbgt, working, shade)
        fall = (not collapsed) and ct_true >= 39.5     # toy: heat collapse
        collapsed = collapsed or fall

        x = MinuteInput(t_min=t, hr=hr, ta_c=ta, rh_pct=rh,
                        enmo_mg=worker.wrist_motion(working), fall_event=fall)
        a = engine.update(x)
        if trace is not None:
            trace.append((engine.profile, x, a, False))
        level = a.level
        work_min = a.work_min_per_hour
        if follow and level == Level.RED:
            stopped = True
        rows.append({
            "t_min": t, "clock": clock(t), "ta_c": ta, "rh_pct": rh,
            "wbgt_eff": rnd(a.wbgt_eff), "limit": rnd(a.wbgt_limit),
            "working": int(working), "ct_true": round(ct_true, 2),
            "hr": round(hr), "ct_est": rnd(a.ct_est), "psi": rnd(a.psi),
            "level": a.level.name, "action": a.action,
            "work_min_per_hour": a.work_min_per_hour,
            "reasons": ";".join(a.reasons), "collapse": int(fall),
        })
    return rows


def rnd(x: float | None, n: int = 2) -> float | None:
    return None if x is None else round(x, n)


def clock(t: int) -> str:
    h = SHIFT_START_H + t / 60
    return f"{int(h):02d}:{int(round((h % 1) * 60)) % 60:02d}"


def summarise(name: str, rows: list[dict]) -> None:
    print(f"\n=== {name} ===")
    print(f"{'time':>5} {'WBGT':>5} {'lim':>5} {'work':>4} {'CTtrue':>6} {'CTest':>6} "
          f"{'HR':>4} {'PSI':>4}  level   action")
    prev = None
    for r in rows:
        if r["level"] != prev or r["t_min"] % 30 == 0:
            print(f"{r['clock']:>5} {r['wbgt_eff']:5.1f} {r['limit']:5.1f} {r['working']:>4} "
                  f"{r['ct_true']:6.2f} {r['ct_est'] if r['ct_est'] else '-':>6} {r['hr']:>4} "
                  f"{r['psi'] if r['psi'] is not None else '-':>4}  {r['level']:<7} {r['action']}"
                  + (f" ({r['work_min_per_hour']} min/h)" if r['action'] == 'work_rest' else ""))
            prev = r["level"]
    peak = max(r["ct_true"] for r in rows)
    over = sum(r["ct_true"] >= 38.5 for r in rows)
    worked = sum(r["working"] for r in rows)
    collapse = next((r["clock"] for r in rows if r["collapse"]), None)
    first_red = next((r["clock"] for r in rows if r["level"] == "RED"), None)
    print(f"-> peak true core temp {peak:.2f} °C, {over} min at/above 38.5 °C, "
          f"{worked} min worked, first RED {first_red or 'never'}, "
          f"collapse {collapse or 'none'}")


def main() -> None:
    out = Path(__file__).parent / "out"
    out.mkdir(exist_ok=True)
    scenarios = (
        ("A_acclimatized_ignores_warnings", False, 10),
        ("B_acclimatized_follows_warnings", True, 10),
        ("C_new_worker_day2_follows_warnings", True, 2),
    )
    for name, follow, days in scenarios:
        rows = run(follow, days)
        with open(out / f"{name}.csv", "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
        summarise(name, rows)
    print(f"\nCSV written to {out}")


if __name__ == "__main__":
    main()
