"""Physiological Strain Index (PSI).

Moran DS, Shitzer A, Pandolf KB (1998). A physiological strain index to
evaluate heat stress. Am. J. Physiol. 275:R129-R134.

PSI = 5·(CT − CT0)/(39.5 − CT0) + 5·(HR − HR0)/(180 − HR0), 0..10.
Here CT is the ECTemp estimate, not a measured rectal temperature.
"""
from __future__ import annotations


def _part(x: float, x0: float, x_max: float) -> float:
    span = x_max - x0
    if span <= 0:
        return 5.0
    return min(5.0, max(0.0, 5.0 * (x - x0) / span))


def psi(ct: float, hr: float, ct0: float, hr0: float) -> float:
    return _part(ct, ct0, 39.5) + _part(hr, hr0, 180.0)


def psi_band(value: float) -> str:
    """Moran's verbal bands."""
    if value < 3:
        return "none_or_little"
    if value < 5:
        return "low"
    if value < 7:
        return "moderate"
    if value < 9:
        return "high"
    return "very_high"
