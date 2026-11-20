"""Core body temperature estimated from heart rate (ECTemp).

Buller MJ, Tharion WJ, Cheuvront SN, et al. (2013). Estimation of human core
temperature from sequential heart rate observations. Physiol. Meas. 34:781-798.

A one-state Kalman filter fed one heart-rate value per minute. Published
accuracy is about 0.3 °C RMSE against ingested/rectal probes, in soldiers and
athletes. It has NOT been validated for Gulf outdoor workers; treat the output
as an estimate with an uncertainty band, never as a measured temperature.
"""
from __future__ import annotations

from dataclasses import dataclass
import math

# Published model constants (1-minute time step).
A = 1.0
GAMMA = 0.022 ** 2                         # process variance, °C² per minute
B0, B1, B2 = -7887.1, 384.4286, -4.5714    # expected HR = B2·CT² + B1·CT + B0
SIGMA = 18.88 ** 2                         # observation variance, bpm²
CT_START = 37.1                            # °C
V_START = 0.0


def expected_hr(ct: float) -> float:
    """Heart rate the model expects at core temperature `ct`."""
    return B2 * ct * ct + B1 * ct + B0


def steady_state_ct(hr: float) -> float:
    """Core temperature the filter settles at if `hr` is held constant.

    The lower root of the observation quadratic. Used in tests and as a
    sanity reference, not in the filter itself.
    """
    c = B0 - hr
    disc = B1 * B1 - 4 * B2 * c
    if disc < 0:
        raise ValueError("heart rate outside the model's range")
    return (-B1 + math.sqrt(disc)) / (2 * B2)


@dataclass
class ECTemp:
    ct: float = CT_START
    v: float = V_START

    def predict(self) -> float:
        """Advance one minute with no heart-rate reading (uncertainty grows)."""
        self.ct = A * self.ct
        self.v = A * A * self.v + GAMMA
        return self.ct

    def update(self, hr: float) -> float:
        """Advance one minute and correct with a heart-rate reading."""
        self.predict()
        m = 2 * B2 * self.ct + B1
        k = self.v * m / (m * m * self.v + SIGMA)
        self.ct += k * (hr - expected_hr(self.ct))
        self.v *= 1 - k * m
        return self.ct

    @property
    def sd(self) -> float:
        """Filter standard deviation, °C. Model error (~0.3 °C) comes on top."""
        return math.sqrt(self.v)
