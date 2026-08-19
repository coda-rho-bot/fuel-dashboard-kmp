#!/usr/bin/env bash
#
# Vendored from coda/ci-scripts@main (2026-08-19) — repos are private now and
# Woodpecker cannot authenticate cross-repo clones; canonical copy remains in
# coda/ci-scripts. Re-vendor on change.
# Validates that commit messages follow Conventional Commits spec.
# Used in CI to enforce consistent commit history for automated versioning.
#
# Conventional Commits: https://www.conventionalcommits.org/
# Format: type(scope)!: description
#
# Valid types: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert
# Scope is optional. ! indicates breaking change.

set -euo pipefail

VALID_TYPES="feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert"
PATTERN="^(${VALID_TYPES})(\(.+\))?!?: .{1,100}"

# Files to check
COMMIT_RANGE="${1:-}"

if [ -z "$COMMIT_RANGE" ]; then
    echo "No commit range specified. Skipping validation."
    exit 0
fi

echo "Validating conventional commits in range: $COMMIT_RANGE"

FAILED=0
COMMITS=$(git log --format="%H %s" "$COMMIT_RANGE" 2>/dev/null || true)

if [ -z "$COMMITS" ]; then
    echo "No commits found in range."
    exit 0
fi

while IFS= read -r line; do
    SHA="${line%% *}"
    MSG="${line#* }"

    # Skip merge commits (pull requests, branches, remotes, and SHA-to-SHA auto-merges)
    if [[ "$MSG" =~ ^Merge\ (pull\ request|branch|remote) ]]; then
        continue
    fi
    if [[ "$MSG" =~ ^Merge\ [0-9a-f]+\ into\ [0-9a-f]+$ ]]; then
        continue
    fi

    # Skip Dependabot commits (already conventional)
    if [[ "$MSG" =~ ^chore\(deps?\) ]]; then
        continue
    fi

    if ! echo "$MSG" | grep -qE "$PATTERN"; then
        echo "FAIL: $SHA"
        echo "  Message: $MSG"
        echo "  Expected: type(scope)!: description"
        echo "  Valid types: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert"
        FAILED=1
    else
        echo "  OK: $MSG"
    fi
done <<< "$COMMITS"

if [ $FAILED -ne 0 ]; then
    echo ""
    echo "Commit message validation FAILED."
    echo "See: https://www.conventionalcommits.org/"
    exit 1
fi

echo "All commits valid."

