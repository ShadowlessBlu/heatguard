"""Unit tests for the heat-risk engine. Run from HeatRisk/:  python -m unittest -v"""
import unittest

from heatrisk import HeatRiskEngine, Level, MinuteInput, WorkerProfile, WorkClass
from heatrisk.ectemp import ECTemp, CT_START, expected_hr, steady_state_ct
from heatrisk.environment import wbgt_bom, wbgt_estimate, wet_bulb_stull
from heatrisk.psi import psi
from heatrisk.workload import (action_limit, enmo_mg, tlv, wbgt_limit,
                               work_class_from_enmo, work_minutes_per_hour)


class TestECTemp(unittest.TestCase):
    def test_starts_at_published_prior(self):
        self.assertEqual(ECTemp().ct, CT_START)

    def test_quadratic_matches_paper_at_prior(self):
        # Model expects ~83 bpm at 37.1 °C.
        self.assertAlmostEqual(expected_hr(37.1), 83.1, delta=0.2)

    def test_converges_to_steady_state(self):
        f = ECTemp()
        for _ in range(600):
            f.update(120.0)
        self.assertAlmostEqual(f.ct, steady_state_ct(120.0), delta=0.05)

    def test_higher_hr_gives_higher_ct(self):
        lo, hi = ECTemp(), ECTemp()
        for _ in range(60):
            lo.update(90.0)
            hi.update(150.0)
        self.assertGreater(hi.ct, lo.ct + 0.3)

    def test_ct_responds_gradually(self):
        f = ECTemp()
        f.update(180.0)
        self.assertLess(f.ct - CT_START, 0.1)    # one minute of high HR barely moves it

    def test_predict_only_keeps_estimate_and_grows_uncertainty(self):
        f = ECTemp()
        for _ in range(30):
            f.update(130.0)
        ct, sd = f.ct, f.sd
        for _ in range(10):
            f.predict()
        self.assertEqual(f.ct, ct)
        self.assertGreater(f.sd, sd)


class TestEnvironment(unittest.TestCase):
    def test_stull_reference_point(self):
        # Stull (2011): 20 °C, 50 % RH -> 13.7 °C.
        self.assertAlmostEqual(wet_bulb_stull(20.0, 50.0), 13.7, delta=0.1)

    def test_bom_rises_with_humidity(self):
        self.assertGreater(wbgt_bom(38, 60), wbgt_bom(38, 20))

    def test_globe_only_raises_estimate(self):
        no_globe = wbgt_estimate(35, 40)
        self.assertGreaterEqual(wbgt_estimate(35, 40, tg_c=60), no_globe)
        self.assertEqual(wbgt_estimate(35, 40, tg_c=20), no_globe)  # max() keeps the safer one

    def test_missing_inputs_are_none(self):
        self.assertIsNone(wbgt_estimate(None, 40))
        self.assertIsNone(wbgt_estimate(35, None))


class TestWorkload(unittest.TestCase):
    def test_acgih_values_moderate_work(self):
        self.assertAlmostEqual(tlv(300), 28.2, delta=0.1)
        self.assertAlmostEqual(action_limit(300), 25.0, delta=0.1)

    def test_limit_interpolates_with_acclimatization(self):
        self.assertAlmostEqual(wbgt_limit(300, 0.0), action_limit(300))
        self.assertAlmostEqual(wbgt_limit(300, 1.0), tlv(300))

    def test_enmo(self):
        self.assertEqual(enmo_mg([(0, 0, 1.0)] * 50), 0.0)
        self.assertAlmostEqual(enmo_mg([(0, 0, 1.2)] * 50), 200.0, places=6)
        self.assertIsNone(enmo_mg([]))

    def test_enmo_classes(self):
        self.assertEqual(work_class_from_enmo(10), WorkClass.REST)
        self.assertEqual(work_class_from_enmo(60), WorkClass.LIGHT)
        self.assertEqual(work_class_from_enmo(200), WorkClass.MODERATE)
        self.assertEqual(work_class_from_enmo(500), WorkClass.HEAVY)

    def test_work_rest_schedule(self):
        self.assertEqual(work_minutes_per_hour(20.0, WorkClass.MODERATE, 1.0), 60)
        self.assertEqual(work_minutes_per_hour(36.0, WorkClass.MODERATE, 1.0), 0)
        mins = [work_minutes_per_hour(w, WorkClass.MODERATE, 1.0) for w in range(24, 34)]
        self.assertEqual(mins, sorted(mins, reverse=True))   # hotter -> less work
        self.assertTrue(any(0 < m < 60 for m in mins))


class TestPSI(unittest.TestCase):
    def test_bounds(self):
        self.assertEqual(psi(37.1, 70, 37.1, 70), 0.0)
        self.assertEqual(psi(39.5, 180, 37.1, 70), 10.0)
        self.assertEqual(psi(41.0, 200, 37.1, 70), 10.0)
        self.assertEqual(psi(36.5, 60, 37.1, 70), 0.0)


def run(engine, minutes, **kw):
    out = []
    for t in minutes:
        out.append(engine.update(MinuteInput(t_min=t, **kw)))
    return out


class TestEngine(unittest.TestCase):
    def test_cool_calm_shift_stays_green(self):
        e = HeatRiskEngine(WorkerProfile(age=30, days_acclimatized=10,
                                         declared_work=WorkClass.LIGHT))
        res = run(e, range(120), hr=80, ta_c=24, rh_pct=40, enmo_mg=50)
        self.assertTrue(all(a.level == Level.GREEN for a in res))
        self.assertEqual(res[-1].action, "keep_going")

    def test_hot_site_without_hr_is_orange_and_says_hr_missing(self):
        e = HeatRiskEngine(WorkerProfile(days_acclimatized=0))
        a = e.update(MinuteInput(t_min=0, ta_c=42, rh_pct=40))
        self.assertEqual(a.level, Level.ORANGE)
        self.assertIn("env_no_safe_work", a.reasons)
        self.assertEqual(a.work_min_per_hour, 0)
        self.assertIn("hr_not_measured", a.reasons)
        self.assertIsNone(a.ct_est)       # not measured is None, never a number
        self.assertIsNone(a.psi)

    def test_warm_site_gives_work_rest_schedule_not_stop(self):
        e = HeatRiskEngine(WorkerProfile(days_acclimatized=2))
        a = e.update(MinuteInput(t_min=0, wbgt_c=28.0))
        self.assertEqual(a.level, Level.YELLOW)
        self.assertIn("env_over_limit", a.reasons)
        self.assertEqual(a.action, "work_rest")
        self.assertTrue(0 < a.work_min_per_hour < 60)

    def test_nothing_measured_is_not_reported_as_safe_data(self):
        a = HeatRiskEngine().update(MinuteInput(t_min=0))
        self.assertIn("env_not_measured", a.reasons)
        self.assertIn("hr_not_measured", a.reasons)
        self.assertIsNone(a.wbgt_eff)

    def test_low_quality_hr_is_ignored(self):
        e = HeatRiskEngine()
        a = e.update(MinuteInput(t_min=0, hr=170, hr_quality=0.2))
        self.assertIsNone(a.hr)
        self.assertIn("hr_not_measured", a.reasons)

    def test_rising_hr_escalates_to_red(self):
        e = HeatRiskEngine(WorkerProfile(age=30, resting_hr=70, days_acclimatized=10))
        levels = []
        for t in range(240):
            hr = min(175, 80 + t * 0.5)
            levels.append(e.update(MinuteInput(t_min=t, hr=hr, ta_c=26, rh_pct=30)).level)
        self.assertEqual(levels[0], Level.GREEN)
        self.assertEqual(levels[-1], Level.RED)
        first = {lv: levels.index(lv) for lv in Level if lv in levels}
        self.assertLess(first[Level.YELLOW], first[Level.ORANGE])
        self.assertLess(first[Level.ORANGE], first[Level.RED])

    def test_unacclimatized_worker_warned_earlier(self):
        def first_red(days):
            e = HeatRiskEngine(WorkerProfile(resting_hr=70, days_acclimatized=days))
            for t in range(400):
                a = e.update(MinuteInput(t_min=t, hr=min(175, 80 + t * 0.4)))
                if a.level == Level.RED:
                    return t
        self.assertLess(first_red(0), first_red(10))

    def test_sustained_hr_over_age_limit_is_red(self):
        e = HeatRiskEngine(WorkerProfile(age=40, resting_hr=70, days_acclimatized=10))
        res = run(e, range(5), hr=150)        # 180 - 40 = 140
        self.assertEqual(res[-1].level, Level.RED)
        self.assertIn("hr_sustained_high", res[-1].reasons)

    def test_deescalation_waits_for_cooldown(self):
        e = HeatRiskEngine(WorkerProfile(days_acclimatized=10), cooldown_min=10)
        run(e, range(3), ta_c=44, rh_pct=40)              # ORANGE
        res = run(e, range(3, 20), ta_c=20, rh_pct=30)    # cool
        self.assertEqual(res[0].level, Level.ORANGE)
        self.assertEqual(res[8].level, Level.ORANGE)      # 9 cool minutes: still held
        self.assertEqual(res[9].level, Level.GREEN)       # 10th: released

    def test_collapse_in_heat_latched_until_ack(self):
        e = HeatRiskEngine(WorkerProfile(days_acclimatized=0), cooldown_min=1)
        e.update(MinuteInput(t_min=0, ta_c=40, rh_pct=40))
        a = e.update(MinuteInput(t_min=1, ta_c=40, rh_pct=40, fall_event=True))
        self.assertEqual(a.level, Level.RED)
        self.assertEqual(a.action, "emergency")
        self.assertIn("collapse_in_heat", a.reasons)
        res = run(e, range(2, 30), ta_c=20, rh_pct=30)
        self.assertTrue(all(r.level == Level.RED for r in res))
        e.acknowledge()
        self.assertEqual(e.update(MinuteInput(t_min=30, ta_c=20, rh_pct=30)).level, Level.GREEN)

    def test_fall_without_heat_is_red_but_not_heat_emergency(self):
        e = HeatRiskEngine()
        a = e.update(MinuteInput(t_min=0, ta_c=20, rh_pct=30, fall_event=True))
        self.assertEqual(a.level, Level.RED)
        self.assertIn("fall", a.reasons)
        self.assertEqual(a.action, "stop_get_help")

    def test_supervisor_view_has_no_vitals(self):
        a = HeatRiskEngine().update(MinuteInput(t_min=0, hr=90, ta_c=30, rh_pct=40))
        view = a.supervisor_view()
        self.assertEqual(set(view), {"t_min", "level", "action", "work_min_per_hour"})

    def test_measured_motion_can_only_raise_declared_work(self):
        e = HeatRiskEngine(WorkerProfile(declared_work=WorkClass.MODERATE))
        self.assertEqual(e.update(MinuteInput(t_min=0, enmo_mg=10)).work_class,
                         WorkClass.MODERATE)
        self.assertEqual(e.update(MinuteInput(t_min=1, enmo_mg=600)).work_class,
                         WorkClass.HEAVY)


if __name__ == "__main__":
    unittest.main()
