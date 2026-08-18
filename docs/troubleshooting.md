# Troubleshooting

Real failure modes, observed in production, with their fixes.

## App crashes at startup: `NoClassDefFoundError`

```
java.lang.NoClassDefFoundError: com/angussoftware/fueldashboard/... 
```

**Cause:** the app launched against a stale/partial jar while Gradle was still writing the build — the class exists in source but not in the jar the JVM loaded. Typically after branch switches or interrupted builds.

**Fix:** clean rebuild, then run:

```bash
cd ~/dev/infra/fuel-dashboard-kmp && ./gradlew :composeApp:clean :composeApp:desktopJar
fuel-dashboard
```

## Startup fails: `BindException: Address already in use`

**Cause:** another dashboard instance already holds port 8322 (often a background process from an earlier session).

**Fix:**

```bash
pkill -9 -f 'composeApp-desktop'   # bracket the pattern if it self-matches
ss -tlnp | grep 8322               # verify free
fuel-dashboard
```

## Conversations show raw UUIDs

Titles resolve through a layered pipeline (summary → `Agent · Date` fallback → by-ID backfill). If you still see UUIDs:

1. **Wait one poll cycle (30s)** — newly-active conversations trigger a background title fetch; the next render shows it
2. **Check metering is enabled** — titles backfill for conversations that have *usage records*; without a usage source there's nothing to backfill from
3. **The Letta conversations *list* is unreliable** — the dashboard fetches by ID as a workaround; if a specific conversation stays raw for >10 minutes, it likely has no usage records either (open it once, or report usage for it)

## Intel timeline is empty

**Not a bug if the day was quiet** — events fire on drops ≥ the threshold (default 1%). Verify the pipeline is alive first:

- Overview tab updating (polls every 30s)?
- Database receiving snapshots? (`fuel_snapshots` grows continuously)

If both are true, an empty timeline means no threshold-crossing events. Tune **Settings → Intelligence → drop threshold** down (0.5) for a fuller timeline.

## Wasted quota shows unexpected values

- **High waste is not an error** — it means quota windows expired with fuel remaining (unused capacity). Cross-check with the advisor: surplus regime = high expected waste.
- **Missing days** — the dashboard must have been running to observe window expiries; gaps in uptime show as missing daily rows.
- **Window counts** — a full day has ~4-5 five-hour windows (z.ai) or 1 daily window (Letta). Fewer means some expiries weren't observed.

## Agents show "disconnected" / no ACP agents

The dashboard maintains ACP sessions via `letta-acp` bridges. If all agents show disconnected:

1. The Letta desktop app may need a restart (ACP runtime exhaustion — known after heavy multi-agent use)
2. Check `letta-acp` binary is on PATH and the agent configs in Settings → Agents are correct
3. Status dots recover automatically (retry with backoff: 10s → 30s → 60s → 120s)

## "config only" agents

Dimmed agent cards with a "config only" badge came from a settings sync — there's no live ACP session for them. They show metered usage if any exists but can't be interacted with. Remove them from the Agents tab if unwanted.

## Mobile app shows none of the new features

The Android app is a build, not a webpage — it needs re-installing to pick up new features. Re-build and re-install the APK; settings/data sync via QR so nothing is lost.

## Build warning: KMP dependency resolution (appleMain)

```
Couldn't resolve dependency 'com.angussoftware.theming:compose' in 'appleMain'
```

**Known noise** — the theming library publishes no iOS artifacts; the desktop/Android targets are unaffected. Builds continue past it.
