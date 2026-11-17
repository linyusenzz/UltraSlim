#!/usr/bin/env python3
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from collect_performance import distribution, parse_pss, read_device_epoch, summarize


class PerformanceTest(unittest.TestCase):
    def test_pss_handles_old_and_new_formats_without_confusing_rss(self):
        self.assertEqual(1234, parse_pss("TOTAL PSS: 1234 TOTAL RSS: 9876"))
        self.assertEqual(1234, parse_pss("  TOTAL    1234  234  567\n"))
        self.assertIsNone(parse_pss("No process found for: app.ultraslim.verifyqa"))

    def test_nearest_rank_preserves_measured_values(self):
        self.assertEqual(19, distribution(list(range(1, 21)))["p95NearestRank"])
        self.assertIsNone(distribution([])["median"])

    def test_summary_separates_outcomes_and_does_not_fill_missing_stages(self):
        log = '\n'.join([
            'I {"event":"conversion","format":"HEIC","outcome":"DONE","totalMs":20,"stagesMs":{"publish":5}}',
            'I {"event":"conversion","format":"HEIC","outcome":"SKIPPED","totalMs":2,"stagesMs":{}}',
            'I {"event":"conversion","format":"HEIC","outcome":"DONE","totalMs":10,"stagesMs":{}}',
            'I {"event":"conversion",',  # Truncated records are excluded.
            'I operation=transcode stage=read elapsed_ms=1.250 bytes=64000',
        ])
        result = summarize(log, [{"pssKiB":None}, {"pssKiB":20}, {"pssKiB":30}])
        self.assertEqual(30, result["peakSampledPssKiB"])
        self.assertEqual(2, result["pssSampleCount"])
        self.assertEqual(15, result["conversions"]["HEIC:DONE"]["totalMs"]["median"])
        self.assertEqual(1, result["conversions"]["HEIC:DONE"]["stagesMs"]["publish"]["count"])
        self.assertEqual(1.25, result["nativeStageMs"]["transcode:read"]["median"])
        self.assertIsNone(summarize("", [])["peakSampledPssKiB"])

    def test_old_logcat_records_are_retained_only_in_raw_data(self):
        old = '99.5 1 1 I tag: operation=probe stage=total elapsed_ms=40 bytes=0'
        new = '100.5 1 1 I tag: operation=probe stage=total elapsed_ms=2 bytes=0'
        result = summarize(old + '\n' + new, [], since_epoch=100)
        self.assertEqual({"count": 1, "median": 2.0, "p95NearestRank": 2.0},
                         result["nativeStageMs"]["probe:total"])

    def test_log_boundary_uses_device_time_even_when_host_clock_is_ahead_or_behind(self):
        device_epoch = 1_700_000_000.5
        old = f'{device_epoch - 1} 1 1 I tag: operation=probe stage=total elapsed_ms=40 bytes=0'
        current = f'{device_epoch + 1} 1 1 I tag: operation=probe stage=total elapsed_ms=2 bytes=0'
        for host_offset in (-600, 600):
            with self.subTest(host_offset=host_offset), patch('collect_performance.time.time',
                    return_value=device_epoch + host_offset) as host_clock:
                commands = []

                def device_run(*command):
                    commands.append(command)
                    return SimpleNamespace(stdout='1700000000.500000000\r\n')

                boundary, raw = read_device_epoch(device_run)
                result = summarize(old + '\n' + current, [], since_epoch=boundary)
                self.assertEqual(device_epoch, boundary)
                self.assertEqual('1700000000.500000000', raw)
                self.assertEqual([('shell', 'date', '+%s.%N')], commands)
                self.assertEqual({"count": 1, "median": 2.0, "p95NearestRank": 2.0},
                                 result["nativeStageMs"]["probe:total"])
                host_clock.assert_not_called()

    def test_unsupported_device_clock_fails_without_falling_back_to_host_time(self):
        for raw in ('', '1700000000.%N', '1700000000', 'NaN', 'date: unsupported format',
                    '1700000000.1\n1700000000.2'):
            with self.subTest(raw=raw), patch('collect_performance.time.time') as host_clock:
                with self.assertRaisesRegex(RuntimeError, 'no capture properties changed'):
                    read_device_epoch(lambda *args: SimpleNamespace(stdout=raw))
                host_clock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
