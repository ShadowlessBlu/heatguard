"""Heat stress of the environment: WBGT estimates from cheap sensors.

The site beacon carries an air temperature / humidity sensor and, optionally,
a black-globe probe (DS18B20 in a matt-black ball). A true WBGT needs a natural
wet-bulb thermometer, which we don't have, so every function here is an
ESTIMATE. When several estimates are available we take the highest: for a
safety warning, overestimating heat is the safer error.
"""
from __future__ import annotations

import math


def saturation_vp_hpa(t_c: float) -> float:
    """Saturation vapour pressure over water, hPa (form used by the Australian BoM)."""
    return 6.105 * math.exp(17.27 * t_c / (237.7 + t_c))


def wet_bulb_stull(t_c: float, rh_pct: float) -> float:
    """Psychrometric wet-bulb temperature at sea level.

    Stull R (2011). Wet-bulb temperature from relative humidity and air
    temperature. J. Appl. Meteor. Climatol. 50:2267-2269. Valid for RH 5-99 %,
    T −20..50 °C; error within about 1 °C.
    """
    rh = min(99.0, max(5.0, rh_pct))
    t = t_c
    return (t * math.atan(0.151977 * math.sqrt(rh + 8.313659))
            + math.atan(t + rh) - math.atan(rh - 1.676331)
            + 0.00391838 * rh ** 1.5 * math.atan(0.023101 * rh)
            - 4.686035)


def wbgt_bom(t_c: float, rh_pct: float) -> float:
    """Australian Bureau of Meteorology simplified WBGT (no sun/wind inputs).

    WBGT ≈ 0.567·Ta + 0.393·e + 3.94, e = vapour pressure in hPa. Intended for
    moderately sunny, light-wind conditions.
    """
    e = rh_pct / 100.0 * saturation_vp_hpa(t_c)
    return 0.567 * t_c + 0.393 * e + 3.94


def wbgt_outdoor_globe(t_c: float, rh_pct: float, tg_c: float) -> float:
    """ISO 7243 outdoor form, 0.7·Tnw + 0.2·Tg + 0.1·Ta, with the psychrometric
    wet bulb standing in for the natural wet bulb. In sun the natural wet bulb
    runs warmer, so this tends to read LOW — hence the max() in `wbgt_estimate`.
    """
    return 0.7 * wet_bulb_stull(t_c, rh_pct) + 0.2 * tg_c + 0.1 * t_c


def wbgt_estimate(t_c: float | None, rh_pct: float | None,
                  tg_c: float | None = None) -> float | None:
    """Best available WBGT estimate, °C, or None if air T/RH are missing."""
    if t_c is None or rh_pct is None:
        return None
    estimates = [wbgt_bom(t_c, rh_pct)]
    if tg_c is not None:
        estimates.append(wbgt_outdoor_globe(t_c, rh_pct, tg_c))
    return max(estimates)
