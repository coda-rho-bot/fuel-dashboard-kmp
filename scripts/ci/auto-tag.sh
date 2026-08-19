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
git push "https://x-access-token:${GPR_TOKEN}@github.com/${REPO}.git" "$NEWTAG" 2>&1 || echo "Tag push failed"

