#!/usr/bin/env python3
"""
Bundled Junie balance checker for the Fuel Dashboard app.

Runs the Junie CLI interactively, sends a trivial task, then calls /usage
to retrieve the current credit balance. Outputs parseable text.

Requirements:
  - Junie CLI installed (junie or junie-auth in PATH)
  - python3 with pexpect (pip install pexpect)
  - ~/.junie/auth file present (created by Junie CLI on first login)

Usage:
  python3 junie-credits.py
"""
import os
import re
import sys

try:
    import pexpect
except ImportError:
    print("ERROR: pexpect not installed. Run: pip install pexpect")
    sys.exit(1)

def find_junie():
    """Find the junie binary, trying junie-auth wrapper first."""
    for name in ["junie-auth", "junie"]:
        for path_dir in os.environ.get("PATH", "").split(os.pathsep):
            full = os.path.join(path_dir, name)
            if os.path.isfile(full) and os.access(full, os.X_OK):
                return full
    # Check common install locations
    home = os.path.expanduser("~")
    candidates = [
        os.path.join(home, ".local", "bin", "junie-auth"),
        os.path.join(home, ".local", "bin", "junie"),
    ]
    for c in candidates:
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None

def main():
    auth_file = os.path.expanduser("~/.junie/auth")
    auth = ""
    if os.path.isfile(auth_file):
        with open(auth_file) as f:
            auth = f.read().strip()

    if not auth:
        print("ERROR: No Junie auth token found at ~/.junie/auth")
        sys.exit(1)

    junie_bin = find_junie()
    if not junie_bin:
        print("ERROR: Junie CLI not found. Install Junie CLI first.")
        sys.exit(1)

    # Use home dir as project (junie requires a project path)
    project = os.path.expanduser("~")
    os.environ["TERM"] = "xterm-256color"

    try:
        child = pexpect.spawn(
            junie_bin,
            ["--auth", auth, "--project", project, "--skip-update-check"],
            timeout=60,
            encoding="utf-8",
            dimensions=(50, 200),
        )
    except Exception as e:
        print(f"ERROR: Failed to start Junie CLI: {e}")
        sys.exit(1)

    try:
        child.expect(["Type your prompt"], timeout=20)
    except Exception:
        import time
        time.sleep(5)

    import time
    time.sleep(1)

    # Run a trivial task to populate session usage
    child.send("echo .")
    child.sendcontrol("m")
    time.sleep(15)

    # Check /usage
    child.send("/usage")
    child.sendcontrol("m")
    time.sleep(10)

    try:
        child.expect(pexpect.TIMEOUT, timeout=8)
    except Exception:
        pass

    output = child.before
    child.sendcontrol("c")
    time.sleep(0.3)
    child.sendcontrol("c")
    child.close(force=True)

    # Strip ANSI escape sequences
    ansi_re = re.compile(
        r"\x1b\[[0-9;]*[a-zA-Z]|\x1b\][^\x07]*\x07|\x1b\[[\?]?[0-9;]*[a-zA-Z]|\x1b[=>]"
    )
    clean = ansi_re.sub("", output)

    lines = [l.strip() for l in clean.split("\n") if l.strip()]

    # Look for balance in task result line: "$XX.XX remaining"
    for line in lines:
        if "remaining" in line.lower() and "$" in line:
            print(line)
            # Try to extract and print structured info
            m = re.search(r"\$(\d+(?:\.\d+)?)\s*remaining", line, re.IGNORECASE)
            if m:
                print(f"Balance left: ${m.group(1)}")

    # Look for /usage output
    in_usage = False
    for line in lines:
        lower = line.lower()
        if "session usage" in lower or "license" in lower or "balance" in lower:
            in_usage = True
        if in_usage:
            if "type your prompt" in lower:
                break
            print(line)

    # Look for license info
    for line in lines:
        if "license" in line.lower() and ("trial" in line.lower() or "license:" in line.lower()):
            print(line)

if __name__ == "__main__":
    main()
