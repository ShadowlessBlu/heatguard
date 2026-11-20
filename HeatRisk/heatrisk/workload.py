"""Workload, heat-exposure limits and work/rest scheduling.

Limits follow the ACGIH TLV for heat stress (continuous forms, 1-hour
time-weighted metabolic rate M in watts):

    TLV (acclimatized)        WBGT = 56.7 − 11.5·log10(M)
    Action limit (not accl.)  WBGT = 59.9 − 14.1·log10(M)

Metabolic rates per class are the ACGIH / ISO 8996 reference values.
Wrist motion classes use Hildebrand et al. (2014) ENMO cut-points
(Med. Sci. Sports Exerc. 46:1816-1824). A wrist under-reads load carrying and
static effort, so the engine takes the higher of the declared task class and
the measured one.
"""
from __future__ import annotations

from enum import IntEnum
import math


class WorkClass(IntEnum):
    REST = 0
    LIGHT = 1
    MODERATE = 2
    HEAVY = 3
    VERY_HEAVY = 4


METABOLIC_W = {
    WorkClass.REST: 115.0,
    WorkClass.LIGHT: 180.0,
    WorkClass.MODERATE: 300.0,
    WorkClass.HEAVY: 415.0,
    WorkClass.VERY_HEAVY: 520.0,
}

# ACGIH clothing adjustment values, °C added to WBGT.
CLOTHING_CAV = {
    "work_clothes": 0.0,
    "cloth_coveralls": 0.0,
    "double_layer_woven": 3.0,
    "sms_polypropylene_coveralls": 0.5,
    "polyolefin_coveralls": 1.0,
    "vapour_barrier_coveralls": 11.0,
}
HOOD_CAV = 1.0

ENMO_LIGHT_MG = 44.8
ENMO_MODERATE_MG = 100.6
ENMO_VIGOROUS_MG = 428.8

ACCLIMATIZATION_DAYS = 7.0   # NIOSH: full acclimatization takes about 7-14 days


def enmo_mg(samples_g: list[tuple[float, float, float]]) -> float | None:
    """Euclidean norm minus one (mg), negatives truncated, averaged over samples."""
    if not samples_g:
        return None
    total = 0.0
    for ax, ay, az in samples_g:
        total += max(0.0, math.sqrt(ax * ax + ay * ay + az * az) - 1.0)
    return 1000.0 * total / len(samples_g)


def work_class_from_enmo(enmo: float) -> WorkClass:
    if enmo < ENMO_LIGHT_MG:
        return WorkClass.REST
    if enmo < ENMO_MODERATE_MG:
        return WorkClass.LIGHT
    if enmo < ENMO_VIGOROUS_MG:
        return WorkClass.MODERATE
    return WorkClass.HEAVY


def acclimatization_fraction(days: float) -> float:
    return min(1.0, max(0.0, days / ACCLIMATIZATION_DAYS))


def _limit_coeffs(accl_frac: float) -> tuple[float, float]:
    """Interpolate between the action limit (0) and the TLV (1)."""
    a = 59.9 + (56.7 - 59.9) * accl_frac
    b = 14.1 + (11.5 - 14.1) * accl_frac
    return a, b


def tlv(m_w: float) -> float:
    return 56.7 - 11.5 * math.log10(m_w)


def action_limit(m_w: float) -> float:
    return 59.9 - 14.1 * math.log10(m_w)


def wbgt_limit(m_w: float, accl_frac: float) -> float:
    a, b = _limit_coeffs(accl_frac)
    return a - b * math.log10(m_w)


def effective_wbgt(wbgt: float, clothing: str = "work_clothes",
                   hood: bool = False) -> float:
    return wbgt + CLOTHING_CAV.get(clothing, 0.0) + (HOOD_CAV if hood else 0.0)


def work_minutes_per_hour(wbgt_eff: float, work: WorkClass,
                          accl_frac: float, step_min: int = 5) -> int:
    """Minutes per hour of `work` that keep the hourly average under the limit.

    Rest is taken as 115 W in the same conditions. If even rest exceeds the
    limit the answer is 0 — cooling has to come from leaving the heat.
    """
    m_rest = METABOLIC_W[WorkClass.REST]
    m_work = METABOLIC_W[work]
    a, b = _limit_coeffs(accl_frac)
    m_max = 10 ** ((a - wbgt_eff) / b)
    if m_max >= m_work:
        return 60
    if m_max <= m_rest or m_work <= m_rest:
        return 0
    frac = (m_max - m_rest) / (m_work - m_rest)
    return int(60 * frac // step_min * step_min)
