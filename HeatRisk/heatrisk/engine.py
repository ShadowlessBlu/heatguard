"""Per-worker heat-risk engine: one call per minute, graded output.

Inputs each minute (any may be missing):
  heart rate + a 0..1 quality score from the wearable's motion gate,
  wrist ENMO (motion intensity), site air temperature / humidity / globe
  temperature (or a WBGT straight from the beacon), and a fall/collapse flag
  from the existing EchoWear fall pipeline.

Output: an `Assessment` with a level, the reasons behind it and one action.

Design rules, carried over from the rest of EchoWear:
  * Unmeasured values are None, never 0. A missing heart rate makes the
    physiological side "not measured"; it does not make the worker "fine".
  * Escalation is immediate. De-escalation waits `cooldown_min` minutes, so a
    single cool reading cannot clear a warning.
  * A collapse is latched at RED until someone acknowledges it.
  * The supervisor view carries the level and the action only — no vitals.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from enum import IntEnum
from statistics import median

from .ectemp import ECTemp
from .environment import wbgt_estimate
from .psi import psi as compute_psi
from .workload import (
    METABOLIC_W, WorkClass, acclimatization_fraction, effective_wbgt,
    wbgt_limit, work_class_from_enmo, work_minutes_per_hour,
)


class Level(IntEnum):
    GREEN = 0
    YELLOW = 1
    ORANGE = 2
    RED = 3


# Plain-language text. Keys are what the app translates; English is the default.
ACTIONS = {
    "keep_going": "Keep working. Drink water every 15-20 minutes.",
    "water_shade": "Drink water now and take a short break in shade.",
    "work_rest": "Pace the work: each hour, work only the minutes shown, rest the rest in shade.",
    "stop_and_cool": "Stop work. Rest in shade, drink water, loosen clothing.",
    "stop_get_help": "Stop now. Move to shade and tell your supervisor.",
    "emergency": "Emergency: possible heat collapse. Call for medical help and start cooling.",
}

REASONS = {
    "env_near_limit": "Site heat is close to the safe limit for this work.",
    "env_over_limit": "Site heat is over the safe limit for continuous work.",
    "env_no_safe_work": "Site heat is too high for this work, even with rest breaks.",
    "env_not_measured": "Site heat not measured.",
    "core_temp_rising": "Estimated core temperature is rising.",
    "core_temp_high": "Estimated core temperature is near the stop limit.",
    "core_temp_limit": "Estimated core temperature reached the stop limit.",
    "strain_low": "Heat strain is low.",
    "strain_moderate": "Heat strain is moderate.",
    "strain_high": "Heat strain is high.",
    "hr_sustained_high": "Heart rate has stayed very high for 5 minutes.",
    "hr_not_measured": "Heart rate not measured recently.",
    "collapse_in_heat": "Fall or collapse detected during heat strain.",
    "fall": "Fall detected.",
}


@dataclass
class WorkerProfile:
    age: int | None = None
    resting_hr: float | None = None        # learned from the shift start if None
    days_acclimatized: float = 0.0
    clothing: str = "work_clothes"
    hood: bool = False
    declared_work: WorkClass = WorkClass.MODERATE


@dataclass
class MinuteInput:
    t_min: int
    hr: float | None = None
    hr_quality: float = 1.0
    enmo_mg: float | None = None
    ta_c: float | None = None
    rh_pct: float | None = None
    tg_c: float | None = None
    wbgt_c: float | None = None            # beacon-computed WBGT overrides T/RH
    fall_event: bool = False


@dataclass
class Assessment:
    t_min: int
    level: Level
    action: str
    reasons: list[str]
    work_min_per_hour: int | None
    ct_est: float | None
    ct_sd: float | None
    psi: float | None
    hr: float | None
    wbgt_eff: float | None
    wbgt_limit: float | None
    work_class: WorkClass

    def supervisor_view(self) -> dict:
        """What a supervisor's crew screen gets. No vitals, by design."""
        return {
            "t_min": self.t_min,
            "level": self.level.name,
            "action": self.action,
            "work_min_per_hour": self.work_min_per_hour,
        }

    def explain(self) -> list[str]:
        return [REASONS[r] for r in self.reasons]


@dataclass
class HeatRiskEngine:
    profile: WorkerProfile = field(default_factory=WorkerProfile)
    cooldown_min: int = 10
    hr_min_quality: float = 0.5
    hr_stale_min: int = 10
    baseline_minutes: int = 5

    def __post_init__(self) -> None:
        self._filter = ECTemp()
        self._ct0 = self._filter.ct
        self._hr0 = self.profile.resting_hr
        self._baseline_hrs: list[float] = []
        self._recent_hr: deque[float] = deque(maxlen=5)
        self._last_valid_hr_t: int | None = None
        self._has_hr = False
        self._level = Level.GREEN
        self._lower_since: int | None = None
        self._lower_window: list[Level] = []
        self._latched: str | None = None

    # ------------------------------------------------------------------ input
    def _valid_hr(self, x: MinuteInput) -> float | None:
        if x.hr is None or x.hr_quality < self.hr_min_quality:
            return None
        if not 30.0 <= x.hr <= 220.0:
            return None
        return x.hr

    def acknowledge(self) -> None:
        """Supervisor or worker confirmed a collapse alert has been handled."""
        self._latched = None

    # ------------------------------------------------------------------- step
    def update(self, x: MinuteInput) -> Assessment:
        p = self.profile
        accl = acclimatization_fraction(p.days_acclimatized)
        reasons: list[str] = []

        # Workload: the higher of declared and measured.
        work = p.declared_work
        if x.enmo_mg is not None:
            work = max(work, work_class_from_enmo(x.enmo_mg))

        # Environment.
        wbgt = x.wbgt_c if x.wbgt_c is not None else wbgt_estimate(x.ta_c, x.rh_pct, x.tg_c)
        env_level: Level | None = None
        wbgt_eff = limit = None
        work_min = None
        if wbgt is None:
            reasons.append("env_not_measured")
        else:
            # Decisions use full precision; rounding is for display only, so
            # the Kotlin port (different rounding rules) cannot disagree.
            wbgt_eff = effective_wbgt(wbgt, p.clothing, p.hood)
            limit = wbgt_limit(METABOLIC_W[work], accl)
            work_min = work_minutes_per_hour(wbgt_eff, work, accl)
            excess = wbgt_eff - limit
            if excess >= 0 and work_min == 0:
                env_level = Level.ORANGE
                reasons.append("env_no_safe_work")
            elif excess >= 0:
                # Over the continuous-work limit, but a work/rest cycle keeps
                # the hourly average under it. Paced work, not a stop.
                env_level = Level.YELLOW
                reasons.append("env_over_limit")
            elif excess >= -2.0:
                env_level = Level.YELLOW
                reasons.append("env_near_limit")
            else:
                env_level = Level.GREEN

        # Physiology.
        hr = self._valid_hr(x)
        if hr is not None:
            self._filter.update(hr)
            self._recent_hr.append(hr)
            self._last_valid_hr_t = x.t_min
            self._has_hr = True
            if self._hr0 is None:
                self._baseline_hrs.append(hr)
                if len(self._baseline_hrs) >= self.baseline_minutes:
                    self._hr0 = min(median(self._baseline_hrs), 100.0)
        else:
            self._filter.predict()

        stale = (self._last_valid_hr_t is None
                 or x.t_min - self._last_valid_hr_t > self.hr_stale_min)
        phys_level: Level | None = None
        ct_est = ct_sd = psi_val = None
        if stale:
            reasons.append("hr_not_measured")
            self._recent_hr.clear()
        else:
            ct_est = self._filter.ct
            ct_sd = self._filter.sd
            phys_level = Level.GREEN
            ct_stop = 38.0 + 0.5 * accl     # ACGIH: 38.0 unacclimatized, 38.5 acclimatized
            if ct_est >= ct_stop:
                phys_level = Level.RED
                reasons.append("core_temp_limit")
            elif ct_est >= ct_stop - 0.3:
                phys_level = Level.ORANGE
                reasons.append("core_temp_high")
            elif ct_est >= ct_stop - 0.6:
                phys_level = Level.YELLOW
                reasons.append("core_temp_rising")

            if self._hr0 is not None and hr is not None:
                psi_val = compute_psi(self._filter.ct, hr, self._ct0, self._hr0)
                if psi_val >= 7:
                    phys_level = max(phys_level, Level.RED)
                    reasons.append("strain_high")
                elif psi_val >= 5:
                    phys_level = max(phys_level, Level.ORANGE)
                    reasons.append("strain_moderate")
                elif psi_val >= 3:
                    phys_level = max(phys_level, Level.YELLOW)
                    reasons.append("strain_low")

            if (p.age is not None and len(self._recent_hr) == 5
                    and sum(self._recent_hr) / 5 > 180 - p.age):
                phys_level = Level.RED
                reasons.append("hr_sustained_high")

        raw = max([lv for lv in (env_level, phys_level) if lv is not None],
                  default=Level.GREEN)

        # Collapse: latched until acknowledged.
        if x.fall_event:
            heat_context = raw >= Level.YELLOW
            self._latched = "collapse_in_heat" if heat_context else "fall"
        if self._latched:
            reasons.append(self._latched)
            raw = Level.RED

        level = self._smooth(raw, x.t_min)
        action = self._action(level, "env_over_limit" in reasons)

        return Assessment(
            t_min=x.t_min, level=level, action=action, reasons=reasons,
            work_min_per_hour=work_min, ct_est=ct_est, ct_sd=ct_sd,
            psi=psi_val, hr=hr, wbgt_eff=wbgt_eff, wbgt_limit=limit,
            work_class=work,
        )

    # ---------------------------------------------------------------- helpers
    def _smooth(self, raw: Level, t: int) -> Level:
        if raw >= self._level:          # escalate (or hold) immediately
            self._level = raw
            self._lower_since = None
            self._lower_window = []
            return self._level
        if self._lower_since is None:
            self._lower_since = t
        self._lower_window.append(raw)
        if t - self._lower_since + 1 >= self.cooldown_min:
            self._level = max(self._lower_window)
            self._lower_since = None
            self._lower_window = []
        return self._level

    def _action(self, level: Level, pace: bool) -> str:
        if level == Level.RED:
            return "emergency" if self._latched == "collapse_in_heat" else "stop_get_help"
        if level == Level.YELLOW:
            return "work_rest" if pace else "water_shade"
        return {Level.GREEN: "keep_going", Level.ORANGE: "stop_and_cool"}[level]
