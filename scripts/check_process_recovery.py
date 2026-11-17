#!/usr/bin/env python3
"""Force-stop only the isolated QA app at an uncommitted Room transaction.

Install matching .verifyqa app/test APKs first, then run with an explicit device serial.
This checks transaction/queue recovery, not native encoding or MediaStore publication.
"""

import argparse
import datetime
import json
import pathlib
import re
import subprocess
import time
import uuid

PACKAGE = "app.ultraslim.verifyqa"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "app.ultraslim.ProcessRecoveryTest"
MARKER = "files/process-recovery-fixture.ready"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit dedicated emulator/test-device serial")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument("--output-dir", type=pathlib.Path)
    args = parser.parse_args()
    output = args.output_dir or pathlib.Path(__file__).resolve().parents[1] / "build" / "process-recovery" / (
        datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]

    def run(*command, timeout=30, check=True):
        return subprocess.run(adb + list(command), capture_output=True, text=True,
                              timeout=timeout, check=check, encoding="utf-8", errors="replace")

    def stage(method, token):
        return ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST + "#" + method,
                "-e", "processRecovery", "true", "-e", "recoveryToken", token, RUNNER]

    # Never accept an arbitrary target package: force-stop must not touch the daily-use app.
    inventory = run("shell", "pm", "list", "instrumentation").stdout
    expected = f"instrumentation:{RUNNER} (target={PACKAGE})"
    if expected not in inventory:
        raise RuntimeError("Install matching app.ultraslim.verifyqa app and test APKs before running")
    run("shell", "run-as", PACKAGE, "pwd")  # Require a debuggable, accessible QA package.
    run("shell", "am", "force-stop", PACKAGE)
    token = str(uuid.uuid4())
    seeded = None
    completed = False
    seed_path = output / "seed-instrumentation.txt"
    try:
        with seed_path.open("w", encoding="utf-8") as seed_log:
            seeded = subprocess.Popen(adb + stage("seedAndWaitForKill", token), stdout=seed_log,
                                      stderr=subprocess.STDOUT, text=True, encoding="utf-8")
            deadline = time.monotonic() + 45
            checkpoint = None
            while time.monotonic() < deadline:
                if seeded.poll() is not None:
                    raise RuntimeError(f"Seed stage exited before its checkpoint; see {seed_path}")
                result = run("shell", "run-as", PACKAGE, "cat", MARKER, timeout=10, check=False)
                if result.returncode == 0:
                    try:
                        candidate = json.loads(result.stdout)
                    except json.JSONDecodeError:
                        candidate = {}
                    if candidate.get("token") == token:
                        checkpoint = candidate
                        break
                time.sleep(0.25)
            if checkpoint is None:
                raise TimeoutError(f"No transaction checkpoint; see {seed_path}")
            (output / "checkpoint.json").write_text(json.dumps(checkpoint, indent=2), encoding="utf-8")
            # This kills the target process while the test APK deliberately holds a transaction open.
            run("shell", "am", "force-stop", PACKAGE)
            seeded.wait(timeout=20)  # An aborted instrumentation result is expected for this stage.

        verification = run(*stage("verifyAfterKill", token), timeout=90, check=False)
        log = verification.stdout + verification.stderr
        verification_path = output / "verify-instrumentation.txt"
        verification_path.write_text(log, encoding="utf-8")
        if verification.returncode != 0 or not re.search(r"OK\s*\(1 test\)", log) or "FAILURES" in log:
            raise RuntimeError(f"Recovery verification failed; see {verification_path}")
        completed = True
        print(f"PASS: real process death, uncommitted Room rollback, and durable queue recovery; logs: {output}")
    finally:
        if not completed:
            run("shell", "am", "force-stop", PACKAGE, check=False)
        if seeded is not None and seeded.poll() is None:
            seeded.terminate()
            try:
                seeded.wait(timeout=5)
            except subprocess.TimeoutExpired:
                seeded.kill()
                seeded.wait(timeout=5)


if __name__ == "__main__":
    main()
