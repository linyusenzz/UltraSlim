#!/usr/bin/env python3
"""Collect opt-in conversion timings and sampled PSS from an already running QA app.

Raw observations are retained. Sampled PSS is not the exact allocation peak, and
instrumented timings are not interchangeable with uninstrumented benchmarks.
"""
import argparse
import datetime
import json
import math
import pathlib
import re
import statistics
import subprocess
import time

PACKAGES = ("app.ultraslim.verifyqa", "app.ultraslim.benchmark")
PROPERTIES = {"log.tag.UltraSlimPerf": "DEBUG", "debug.ultraslim.trace": "1"}


def parse_pss(text):
    for pattern in (r"\bTOTAL PSS:\s*(\d+)", r"(?m)^\s*TOTAL\s+(\d+)\s"):
        match = re.search(pattern, text)
        if match:
            return int(match.group(1))
    return None


def distribution(values):
    if not values:
        return {"count": 0, "median": None, "p95NearestRank": None}
    values = sorted(values)
    return {"count": len(values), "median": statistics.median(values),
            "p95NearestRank": values[math.ceil(len(values) * .95) - 1]}


def read_device_epoch(run):
    """Use the device wall clock, which timestamps logcat, never the host clock."""
    raw = run("shell", "date", "+%s.%N").stdout.strip()
    if not re.fullmatch(r"\d+\.\d{1,9}", raw):
        raise RuntimeError("Device date did not return a fractional Unix timestamp; no capture properties changed")
    epoch = float(raw)
    if not math.isfinite(epoch):
        raise RuntimeError("Device date returned an invalid Unix timestamp; no capture properties changed")
    return epoch, raw


def summarize(log, samples, since_epoch=None):
    conversions, native = {}, {}
    image_work = []
    for line in log.splitlines():
        if since_epoch is not None:
            timestamp = re.match(r"^\s*(\d+\.\d+)\s", line)
            if timestamp is None or float(timestamp[1]) < since_epoch:
                continue  # -T 1 can include an older logcat record; retain it only in raw logs.
        start = line.find('{')
        if start >= 0:
            try:
                event = json.loads(line[start:])
            except json.JSONDecodeError:
                continue  # A logcat line can be truncated; never invent the missing sample.
            if event.get("event") == "conversion":
                key = f"{event.get('format')}:{event.get('outcome')}"
                conversions.setdefault(key, []).append(event)
            elif event.get("event") == "image_work":
                image_work.append(event)
        match = re.search(r"operation=(\S+) stage=(\S+) elapsed_ms=([\d.]+) bytes=(\d+)", line)
        if match:
            native.setdefault(f"{match[1]}:{match[2]}", []).append(float(match[3]))
    pss = [s["pssKiB"] for s in samples if s.get("pssKiB") is not None]
    return {
        "measurementNotes": ["PSS peak is the maximum observed sample, not the exact process peak",
                             "Stage durations may overlap; do not sum nested native stages",
                             "Timed runs include instrumentation overhead; retain raw data for comparison"],
        "peakSampledPssKiB": max(pss) if pss else None,
        "pssSampleCount": len(pss),
        "conversions": {key: {
            "totalMs": distribution([e["totalMs"] for e in events]),
            "stagesMs": {stage: distribution([e["stagesMs"][stage] for e in events
                                              if stage in e.get("stagesMs", {})])
                         for stage in sorted({s for e in events for s in e.get("stagesMs", {})})},
            "thermalStates": [{"start": e.get("thermalStart"), "end": e.get("thermalEnd")}
                              for e in events],
        } for key, events in conversions.items()},
        "nativeStageMs": {key: distribution(values) for key, values in native.items()},
        "imageWorkWaitMs": distribution([e["waitMs"] for e in image_work]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", choices=PACKAGES, default=PACKAGES[0])
    parser.add_argument("--seconds", type=float, default=120)
    parser.add_argument("--interval", type=float, default=2)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not (0 < args.seconds <= 3600 and 1 <= args.interval <= args.seconds):
        parser.error("Use 1 <= interval <= seconds <= 3600")
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=False)  # Never overwrite a previous measurement.
    adb = [args.adb, "-s", args.serial]

    def run(*command, check=True):
        return subprocess.run(adb + list(command), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=15, check=check)

    pid = run("shell", "pidof", args.package).stdout.strip()
    if not re.fullmatch(r"\d+", pid):
        raise RuntimeError("Open the isolated QA app before collecting; exactly one process is required")
    previous = {key: run("shell", "getprop", key).stdout.strip() for key in PROPERTIES}
    if any(not re.fullmatch(r"[A-Za-z0-9_.-]*", value) for value in previous.values()):
        raise RuntimeError("Unexpected diagnostic property value; no properties changed")
    samples, changed = [], []
    capture = None
    metadata = {"version": 1, "package": args.package, "intervalSeconds": args.interval,
                "requestedSeconds": args.seconds, "startedUtc": datetime.datetime.now(
                    datetime.timezone.utc).isoformat(), "status": "running"}
    log_path = output / "timings.log"
    start = time.monotonic()
    capture_epoch, device_epoch_raw = read_device_epoch(run)
    metadata["captureStartDeviceEpoch"] = capture_epoch
    metadata["captureStartDeviceEpochRaw"] = device_epoch_raw
    metadata["logTimestampClock"] = "device Unix epoch; host startedUtc is informational only"
    try:
        for key, value in PROPERTIES.items():
            # Record before invoking adb so an interrupted setprop is still restored.
            changed.append(key)
            run("shell", "setprop", key, value)
        with log_path.open("w", encoding="utf-8") as log:
            capture = subprocess.Popen(adb + ["logcat", "--pid=" + pid, "-v", "epoch", "-T", "1",
                "UltraSlimPerf:I", "UltraSlimNativeTrace:I", "*:S"], stdout=log,
                stderr=subprocess.STDOUT, text=True, encoding="utf-8")
            print("Collecting the QA process; start the intended conversion batch now.", flush=True)
            with (output / "memory.jsonl").open("w", encoding="utf-8") as memory:
                while time.monotonic() - start < args.seconds:
                    if capture.poll() is not None:
                        raise RuntimeError("logcat stopped; inspect timings.log")
                    if run("shell", "pidof", args.package, check=False).stdout.strip() != pid:
                        raise RuntimeError("QA process restarted or exited; a new capture is required")
                    raw = run("shell", "dumpsys", "meminfo", args.package).stdout
                    sample = {"elapsedSeconds": time.monotonic() - start,
                              "pssKiB": parse_pss(raw), "raw": raw}
                    samples.append(sample)
                    memory.write(json.dumps(sample, ensure_ascii=False) + "\n")
                    memory.flush()
                    time.sleep(max(0, min(args.interval, args.seconds - (time.monotonic() - start))))
        metadata["status"] = "complete"
    finally:
        if metadata["status"] == "running":
            metadata["status"] = "interrupted"
        if capture is not None and capture.poll() is None:
            capture.terminate()
            try:
                capture.wait(timeout=5)
            except subprocess.TimeoutExpired:
                capture.kill()
                capture.wait(timeout=5)
        restoration_errors = []
        for key in reversed(changed):
            try:
                run("shell", "setprop", key, previous[key] or '\"\"')
            except (subprocess.SubprocessError, OSError) as error:
                restoration_errors.append(f"{key}: {error}")
        metadata["restoreErrors"] = restoration_errors
        metadata["elapsedSeconds"] = time.monotonic() - start
        (output / "capture.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        summary = summarize(log_path.read_text(encoding="utf-8") if log_path.exists() else "", samples, capture_epoch)
        (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        if restoration_errors:
            print("Diagnostic property restoration failed: " + "; ".join(restoration_errors))
    print(f"Saved raw observations and summary to {output}")


if __name__ == "__main__":
    main()
