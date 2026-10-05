#!/bin/sh
#
# Vendored from coda/ci-scripts@main (2026-08-19) — repos are private now and
# Woodpecker cannot authenticate cross-repo clones; canonical copy remains in
# coda/ci-scripts. Re-vendor on change.
# Auto-tag script for Angus-Tasks CI
# Extracted to separate file to avoid Woodpecker secret masking issues

TAGS=$(git tag -l 'v*' --sort=-version:refname 2>/dev/null | head -1)

if [ -z "$TAGS" ]; then
  VER="0.0.0"
else
  VER=$(echo "$TAGS" | sed 's/^v//')
fi

LOGMSG=$(git log -1 --format='%s')
BUMPTYPE="skip"

if echo "$LOGMSG" | grep -qE "^[a-z]+(\(.+\))?!:|BREAKING CHANGE:"; then
  BUMPTYPE="major"
elif echo "$LOGMSG" | grep -qE "^feat"; then
  BUMPTYPE="minor"
elif echo "$LOGMSG" | grep -qE "^fix"; then
  BUMPTYPE="patch"
fi

if [ "$BUMPTYPE" = "skip" ]; then
  echo "No version bump needed for: $LOGMSG"
  exit 0
fi

M=$(echo "$VER" | cut -d. -f1)
N=$(echo "$VER" | cut -d. -f2)
P=$(echo "$VER" | cut -d. -f3)

case "$BUMPTYPE" in
  major) M=$((M + 1)); N=0; P=0 ;;
  minor) N=$((N + 1)); P=0 ;;
  patch) P=$((P + 1)) ;;
esac

NEWTAG="v${M}.${N}.${P}"
echo "Bumping version: $VER -> $NEWTAG ($BUMPTYPE)"

git config --global user.email "woodpecker@angussoftware.dev"
git config --global user.name "Woodpecker CI"
git tag "$NEWTAG"
REPO="${REPO:-RhoMancer/Angus-Tasks}"
# Leak-proof (post-#287): GPR_TOKEN consumed via a Python subprocess — the
# old x-access-token:${GPR_TOKEN} URL expanded the secret in command text,
# which Woodpecker's wrapper echoes to logs regardless of set +x. Token is
# OPTIONAL: absent/dead token skips the push gracefully (same semantics as
# angus-tasks PR #84); tag creation still happens locally either way.
if command -v python3 >/dev/null 2>&1; then
  python3 - "$NEWTAG" "$REPO" << 'PYEOF'
import os, subprocess, sys
token = os.environ.get('GPR_TOKEN', '')
if not token:
    print('GitHub tag push skipped (no token)')
    sys.exit(0)
url = f'https://x-access-token:{token}@github.com/{sys.argv[2]}.git'
r = subprocess.run(['git', 'push', url, sys.argv[1]], capture_output=True, text=True)
if r.returncode != 0:
    print(f'Tag push failed: {r.stderr.replace(token, "***").strip()[:200]}')
else:
    print(f'Pushed {sys.argv[1]} to GitHub')
PYEOF
else
  echo "python3 unavailable — skipping GitHub tag push (leak-proof path requires it)"
fi

