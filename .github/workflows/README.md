# NRVA Playback Telemetry Generator (Android)

A nightly system that runs a scripted set of video playback scenarios across every
supported NewRelicVideoAgent (NRVA) Android release, every representative Android
API level, and every current AVD device profile — and lets NRVA push telemetry to
New Relic. The purpose is **not** pass/fail testing. It's to **generate a continuous
stream of realistic NRVA events** so that regressions between our SDK and the
New Relic platform surface within a day, not weeks after a customer complains.

This is the Android sibling of the iOS/tvOS generator at
[`avinash-newrelic/video-agent-iOS/.github/workflows`](https://github.com/avinash-newrelic/video-agent-iOS/blob/master/.github/workflows/README.md).
The design principles are the same; the platform details differ substantially.
See [Android vs iOS: what actually changes](#android-vs-ios-what-actually-changes)
for a side-by-side.

If you're new to this repo or to GitHub Actions, read this end-to-end before
running anything.

---

## Table of contents

1. [What this actually is](#what-this-actually-is)
2. [Prerequisites](#prerequisites)
3. [How the pieces fit together](#how-the-pieces-fit-together)
4. [The coverage matrix](#the-coverage-matrix)
5. [How to run it](#how-to-run-it)
6. [How to read the results](#how-to-read-the-results)
7. [Android vs iOS: what actually changes](#android-vs-ios-what-actually-changes)
8. [Challenges we hit (and how we solved them)](#challenges-we-hit-and-how-we-solved-them)
9. [Sample-app contract (what the app must do)](#sample-app-contract-what-the-app-must-do)
10. [Cost and time expectations](#cost-and-time-expectations)
11. [Troubleshooting](#troubleshooting)
12. [File map](#file-map)

---

## What this actually is

Every night at 22:53 UTC, GitHub triggers a workflow that:

1. Discovers every published NRVA-Android release ≥ v4.1.0 from the upstream
   [`newrelic/video-agent-android`](https://github.com/newrelic/video-agent-android)
   repo.
2. For each version, builds the `NRSampleApp` APK against that version's tag.
3. For each `(version × leg)` pair, boots a KVM-accelerated AVD on an
   `ubuntu-latest` GitHub-hosted runner, installs the APK, and dispatches a
   series of scripted playback scenarios via `am start` intent extras (HLS
   lifecycle, MP4 lifecycle, content error, VMAP ad lifecycle, ad error, and
   MediaTailor SSAI lifecycle).
4. NRVA — running inside the app — pushes its telemetry to New Relic tagged
   with attributes identifying the run, version, leg, and scenario.

By the next morning you have hundreds of events in the New Relic UI, keyed by
`runId`, `nrvaVersion`, `legTag`, and `scenarioId`. If any `(version × leg)`
drops its event count to zero, you know something broke.

A **"leg"** is one row of `(API level, device profile)`. We test 4 legs. Multiplied
by however many current NRVA versions exist (currently 4 — v4.1.0, v4.1.1, v4.2.0,
v4.3.0) = **16 test jobs per nightly run**.

---

## Prerequisites

You need:

- **Write access** to the fork where these workflow files live.
- **`NEW_RELIC_APP_TOKEN`** configured as a GitHub Actions **secret** on the fork
  repo (Settings → Secrets and variables → Actions). This is the mobile agent
  token for the New Relic account that will receive the telemetry. If the secret
  is absent, the sample app boots but NRVA disables itself — the run "succeeds"
  but produces zero events.
- **`NEW_RELIC_COLLECTOR_ADDRESS`** configured as a GitHub Actions **variable**
  (same UI section) if you want telemetry routed somewhere other than the
  default production collector. Typical staging value:
  `staging-mobile-collector.newrelic.com`.

You do NOT need:

- Anything installed on your local machine. All build + test work happens on
  GitHub-hosted `ubuntu-latest` runner VMs.
- A personal New Relic account. Telemetry lands in whichever account owns the
  `NEW_RELIC_APP_TOKEN` secret above.

To run workflows from your terminal (optional but useful):

```bash
brew install gh    # macOS
gh auth login      # once
```

---

## How the pieces fit together

Three workflow files, chained via `workflow_call`:

```
┌────────────────────┐
│ playback-master.yml│  ← the entry point (schedule + workflow_dispatch)
│                    │
│  ┌──────────────┐  │
│  │  discover    │  │  Reads playback-defaults.json + gh release list
│  │   (ubuntu)   │  │  → outputs: versions[], legs[], scenarios
│  └──────┬───────┘  │
│         │ fan-out (matrix)
│  ┌──────▼───────┐  │  For each version:
│  │    build     │  │  builds NRSampleApp.apk against the version tag,
│  │   (ubuntu)   │  │  uploads as artifact. Calls playback-build.yml
│  └──────┬───────┘  │
│         │
│  ┌──────▼───────┐  │  For each (version × leg):
│  │    test      │  │  downloads the .apk, boots AVD, runs scenarios,
│  │   (ubuntu)   │  │  uploads log/summary artifacts.
│  └──────┬───────┘  │  Calls playback-test.yml
│         │
│  ┌──────▼───────┐  │
│  │   summary    │  │  Collates artifacts into a run summary posted to
│  │   (ubuntu)   │  │  the Actions run page.
│  └──────────────┘  │
└────────────────────┘
```

The three files:

- **`playback-master.yml`** — orchestrates everything. Discovers versions, fans
  out into build + test matrices, aggregates a summary.
- **`playback-build.yml`** — reusable child workflow. Given `nrva_version`,
  produces one `NRSampleApp.apk` binary as an artifact by checking out
  `newrelic/video-agent-android@v<version>` and running `assembleRelease`.
- **`playback-test.yml`** — reusable child workflow. Given `(nrva_version, leg)`,
  boots an AVD, installs the APK, runs scenarios, uploads logs.

The matrix (legs, scenarios) is data-driven from **`playback-defaults.json`** —
one source of truth. If you want to add or remove a leg, edit that file, nothing
else.

---

## The coverage matrix

Everything runs on `ubuntu-latest` with `google_apis` `x86_64` system images.

**4 legs — one representative device profile per generation, following the
"least model" rule (base Pixel, not Pro/Fold).**

| Leg tag                        | API level | Android version | Device profile |
|--------------------------------|-----------|-----------------|----------------|
| `android-15-api-35-pixel-8`    | 35        | 15              | Pixel 8        |
| `android-14-api-34-pixel-7`    | 34        | 14              | Pixel 7        |
| `android-11-api-30-pixel-4`    | 30        | 11              | Pixel 4        |
| `android-8-api-26-pixel-2`     | 26        | 8.0 (Oreo)      | Pixel 2        |

**Cross this with current NRVA versions (currently 4.1.0, 4.1.1, 4.2.0, 4.3.0)
→ 16 test jobs per nightly run.**

**6 scenarios per test job**, run sequentially:

- `content-hls-lifecycle` — plays an HLS stream through full lifecycle events
- `content-mp4-lifecycle` — same for progressive MP4
- `content-error` — deliberately triggers a playback error
- `ad-vmap-lifecycle` — plays a VMAP ad break through full IMA ad lifecycle
- `ad-error` — triggers an ad error
- `ssai-mediatailor-lifecycle` — plays an AWS MediaTailor SSAI session end-to-end
  (Android-only; iOS does not currently ship a MediaTailor tracker)

---

## How to run it

### Automatically, on a schedule

Nothing to do — it runs at 22:53 UTC daily. Check the "Actions" tab of the fork
repo the next morning.

### Manually, from the GitHub UI

1. Go to **Actions** tab in the fork repo.
2. Select **playback-master** from the left sidebar.
3. Click **Run workflow** in the top-right.
4. Fill in the fields (all optional):

    | Field           | Meaning                                                    | Example                                |
    |-----------------|------------------------------------------------------------|----------------------------------------|
    | `versions`      | Comma-separated NRVA versions to test                       | `4.3.0,4.1.1`                          |
    | `legs`          | Comma-separated leg tags. Empty = all 4                     | `android-15-api-35-pixel-8`            |
    | `scenarios`     | Comma-separated scenario ids. Empty = default 6            | `content-hls-lifecycle,ad-error`       |
    | `nr_overrides`  | JSON of per-run NRVA config overrides                       | `{"harvest_cycle_secs":5}`             |
    | `min_version`   | Floor for auto-version-discovery (only used if `versions` empty) | `4.1.0`                            |

5. Click the green **Run workflow** button.

### Manually, from your terminal

Once you have `gh` installed and authenticated:

```bash
# Test a single version across all 4 legs
gh workflow run playback-master.yml \
  --repo avinash-newrelic/video-agent-android \
  --ref internal/video-rig \
  -f versions=4.3.0

# Test multiple versions
gh workflow run playback-master.yml \
  --repo avinash-newrelic/video-agent-android \
  --ref internal/video-rig \
  -f versions=4.3.0,4.2.0,4.1.1

# Narrow to specific legs
gh workflow run playback-master.yml \
  --repo avinash-newrelic/video-agent-android \
  --ref internal/video-rig \
  -f versions=4.3.0 \
  -f legs=android-15-api-35-pixel-8,android-8-api-26-pixel-2

# Override NRVA config for a debugging session
gh workflow run playback-master.yml \
  --repo avinash-newrelic/video-agent-android \
  --ref internal/video-rig \
  -f versions=4.3.0 \
  -f nr_overrides='{"harvest_cycle_secs":5}'

# Watch the run you just triggered
gh run watch --repo avinash-newrelic/video-agent-android
```

`nr_overrides` keys are lower-cased NRVA config field names. They're transformed
to intent extras named `NR_<UPPER_KEY>` and passed to the sample-app via
`am start -e`. The sample app reads them and re-configures the NRVA agent
before instrumenting playback.

---

## How to read the results

### On the Actions run page

Each run produces a Markdown **summary** on the Actions run page (scroll down
below the job list). It contains:

- A per-`(version × leg)` grid of scenario counts (`ok / total`, marked ✅ ⚠️ ❌).
- An aggregate line: e.g. `48 / 96 scenarios succeeded · 0 (version × leg) cells missing`.
- A canned NRQL query keyed to the current run.

### Per-leg artifacts

Every `(version × leg)` uploads a `playback-<version>-<legTag>` artifact that contains:

- `SUMMARY.txt` — flat text summary with one row per scenario: `<status>|<elapsed_s>|<scenario>|<viewId>`.
- `logcat.log` — the emulator's full logcat for the duration of all scenarios in this leg.
- Per-scenario subdirectory with:
  - `auto-play-<scenario>.log` — the sample app's own log file (playback events,
    timing, errors it observed).
  - `screenshot.png` — final-state screenshot of the emulator.

Download from the run page or via `gh run download <run-id>`.

### In New Relic

Every NRVA event carries these attributes (set as global attributes on the
tracker before playback starts):

| Attribute      | Value                                     |
|----------------|-------------------------------------------|
| `runId`        | The GitHub Actions run number             |
| `nrvaVersion`  | NRVA version under test                   |
| `legTag`       | e.g. `android-15-api-35-pixel-8`          |
| `scenarioId`   | e.g. `content-hls-lifecycle`              |
| `gitSha`       | Commit under test                         |
| `triggerTime`  | ISO-8601 of when the master run began     |

Sample NRQL to see all events from a specific run:

```sql
SELECT count(*) FROM Mobile_Video WHERE runId = '<run number>'
FACET nrvaVersion, legTag, eventName SINCE 1 day ago
```

Look at a specific playback session:

```sql
SELECT * FROM Mobile_Video WHERE viewId = '<viewId>' SINCE 1 day ago
```

---

## Android vs iOS: what actually changes

The design of the iOS system is the design of this one. Only the platform bits
are different. If you're bouncing between the two, this table is what actually
matters.

| Concern                     | iOS/tvOS                                                              | Android                                                              |
|-----------------------------|-----------------------------------------------------------------------|----------------------------------------------------------------------|
| Runner OS                   | `macos-15` (pinned)                                                   | `ubuntu-latest`                                                      |
| Xcode / SDK pinning         | Xcode `26.3` pinned via `setup-xcode`                                 | Android SDK preinstalled on ubuntu-latest; JDK 17 via `setup-java`   |
| Concurrent runner cap       | **5** macOS runners per billing account                               | **20** Ubuntu runners per billing account                            |
| Runner minute billing       | 10× Linux equivalent                                                  | 1× (this is the baseline)                                            |
| Wall clock for full run     | ~40 min steady-state (48 jobs in 10 waves of 5)                       | ~15-20 min (16 jobs all fit in one wave)                             |
| Virtual device              | iOS/tvOS Simulator                                                    | Android AVD (KVM-accelerated on Linux only)                          |
| Runtime download            | `xcodebuild -downloadPlatform` for older iOS versions (~6 GB, flaky)   | `sdkmanager` for older API-level system-images (~1-2 GB per image)   |
| Runtime cache               | `/Library/Developer/CoreSimulator/**` + daemon-kick after restore     | `~/.android/**` via `actions/cache` — no daemon-kick needed          |
| Device model refresh cycle  | Yearly (iPhone 16→17); leg tags rewrite one line per year             | Yearly-ish; Pixel base model rarely renamed                          |
| Scenario dispatch mechanism | env vars on Simulator launch (`xcrun simctl launch --env`)            | Intent extras (`adb shell am start -e SCENARIO_ID <id>`)             |
| Scenario progress log      | `Documents/logs/auto-play-<id>.log`                                   | `/sdcard/Android/data/<pkg>/files/logs/auto-play-<id>.log`           |
| Build fan-out              | Per (version × platform) — one iOS build, one tvOS build              | Per version only — the same APK runs on any API ≥ minSdk             |
| Platforms tested           | iOS + tvOS                                                            | Android only (no Wear/Auto/TV yet)                                   |
| Number of legs             | 8 (4 iOS + 4 tvOS)                                                    | 4                                                                    |
| SSAI leg                   | Not shipped (no MediaTailor tracker on iOS today)                     | `ssai-mediatailor-lifecycle` — Android-native                        |
| KVM/hardware accel         | Not applicable                                                        | KVM enabled via `udev` rule in the test job                          |

**Design consequences of the concurrency difference.** On iOS, the 5-runner
cap is the binding constraint and drives everything: batching, wave count,
version-matrix pruning. On Android that pressure is essentially gone — 16
jobs fit in one wave. Cost-per-run is also ~10× cheaper because Ubuntu bills
at 1× vs macOS 10×. This is why we can afford to run 6 scenarios per leg
(vs 5 on iOS) and add the MediaTailor scenario without wincing.

---

## Challenges we hit (and how we solved them)

Some of these are inherited lessons from iOS; some are Android-specific.

### 1. `macos-latest` / `ubuntu-latest` drift silently

Same problem as iOS's `macos-latest` critique. Even `ubuntu-latest` moves
without warning. We pin `ubuntu-latest` today for compatibility with
`reactivecircus/android-emulator-runner` (which follows GitHub's supported
matrix). If reproducibility becomes critical, pin `ubuntu-24.04` explicitly.

### 2. KVM is off by default on Ubuntu runners

The AVD hardware acceleration path requires `/dev/kvm` to be user-accessible.
On `ubuntu-latest` this needs a udev rule installed at job time:

```bash
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
  | sudo tee /etc/udev/rules.d/99-kvm4all.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=kvm
```

Without this the emulator falls back to software rendering and a 90-second
playback scenario takes 10 minutes. **Every Android emulator workflow on
Ubuntu needs this step.**

### 3. AVD system images are 1-2 GB and Google's CDN can throttle

Same class of problem as iOS's Simulator runtime downloads, less severe in
magnitude. Fix is layered:

1. **Cache** `~/.android/avd/*` keyed on `(api_level, target, arch, profile)`.
2. **Snapshot** the AVD after first boot and reuse the snapshot on later runs
   in the same job — `reactivecircus/android-emulator-runner` does this
   automatically via its "generate snapshot" step.

Repo-wide cache cap is 10 GB total. Four cached AVDs total ~5-6 GB — well
within budget.

### 4. Generator, not validator

Same explicit design choice as iOS. The test workflow **does not fail** if a
scenario times out. It records the timeout in `SUMMARY.txt` and returns exit 0.

Reason: the telemetry that DID fire before the timeout still landed in New
Relic and is useful. We'd rather have partial data + a soft warning than a
red X and no data. If you build something similar, be deliberate about this
choice: a "pass/fail gate" workflow and a "generate signal" workflow have
opposite failure semantics. **Don't mix them.**

### 5. Older API images sometimes lack architectures on new host CPUs

x86_64 images for API < 24 are getting rare. We're stopping the leg matrix
at API 26 for exactly that reason — API 23 and below would need `x86` (32-bit)
images that are being deprecated.

### 6. `am start` on cold app boot can lose the first extras

Android occasionally races the intent through the app-launch path, and the
sample app's `onCreate` reads `getIntent().getStringExtra(...)` before the
extras are fully attached. **Fix**: sample-app must call `am force-stop`
between scenarios (this workflow does) so every scenario is a cold start
with fully-materialized extras.

---

## Sample-app contract (what the app must do)

This workflow depends on the sample app supporting an env/intent-driven
auto-run mode. As of the branch this README lives on, the sample app in
`app/` is UI-driven only (button clicks in `MainActivity`). Until the
following changes land, every scenario will time out and the run will
produce empty SUMMARY rows.

The changes needed (equivalent to what was done on iOS):

1. **Read `SCENARIO_ID` from intent extras** in `MainActivity.onCreate`. If
   present, skip the UI and immediately launch the correct player activity
   with the scenario's playback URL. Scenarios and their target activities:

    | Scenario id                    | Target activity            | Playback URL kind                      |
    |--------------------------------|----------------------------|----------------------------------------|
    | `content-hls-lifecycle`        | `VideoPlayer`              | HLS m3u8                               |
    | `content-mp4-lifecycle`        | `VideoPlayer`              | Progressive MP4                        |
    | `content-error`                | `VideoPlayer`              | A deliberately invalid URL             |
    | `ad-vmap-lifecycle`            | `VideoPlayerAds`           | Content + IMA VMAP tag                 |
    | `ad-error`                     | `VideoPlayerAds`           | Content + broken IMA VMAP tag          |
    | `ssai-mediatailor-lifecycle`   | `VideoPlayerMediaTailor`   | MediaTailor session URL                |

2. **Read `NR_*` intent extras** (case-preserved) and apply them to the
   `NRVideoConfiguration.Builder` before `NRVideo.build()`. `NR_HARVEST_CYCLE_SECS=5`
   should map to `.withHarvestCycle(5)`, etc.

3. **Set NRVA global attributes** from intent extras: `runId`, `nrvaVersion`,
   `legTag`, `scenarioId`, `gitSha`, `triggerTime` — via
   `NRVideo.setAttribute(...)` at agent init time so every event carries them.

4. **Write scenario progress** to
   `getExternalFilesDir(null) + "/logs/auto-play-<scenario>.log"`. First line
   must be `VIEW_ID:<uuid>` (whatever `NRVideo.getCurrentViewId()` returns).
   Last line must be `SCENARIO_DONE:<scenario>` — this is the sentinel the
   workflow polls for.

5. **Terminate the scenario cleanly.** After emitting `SCENARIO_DONE`, the
   scenario should stop the player and let the workflow's next `force-stop`
   kill the process for the next iteration.

Once these are in place, the workflow runs end-to-end unchanged.

---

## Cost and time expectations

Numbers assume free/Pro/Team GitHub plan (20 concurrent Ubuntu runners).

### Wall clock

- **Day 1 (all caches cold)**: ~25-30 min. Most of the time is one-time
  system-image downloads across the four API levels.
- **Steady state (day 2 onward, cache warm)**: **~15-20 min.**
- **Single-version dispatch (1 version × 4 legs)**: ~10 min.

### Ubuntu runner minutes billed

Each ubuntu-latest job minute bills at 1× (baseline). For a 16-job nightly run:

- 4 build jobs × ~3 min = 12 min
- 16 test jobs × ~12 min steady-state = 192 min
- Total: ~200 Linux-min/night
- Monthly: **~6,000 Linux-min/month**

That's ~2× the free plan's 3,000 min/month cap. Options:

- Run on a paid plan (essentially free at this scale).
- Reduce cadence to weekly full sweep + daily latest-version-only.
- Drop older API-level legs as they become irrelevant.

For comparison: the iOS system with the same cadence burns ~63,000
Linux-equivalent min/month because of the 10× macOS multiplier.

### Storage

- Cache: ~5-6 GB used continuously (4 AVDs).
- Artifacts: each run uploads ~16 artifacts × ~10 MB = ~160 MB/night.
  Retention: 14 days for playback artifacts, 7 days for APKs.

---

## Troubleshooting

### "Android SDK not found" or "sdkmanager not on PATH"

Cause: pinned an ubuntu image that predates the current SDK layout.
Fix: use `ubuntu-latest` or `ubuntu-24.04` — anything older is unsupported
by `reactivecircus/android-emulator-runner`.

### "Cannot boot AVD: /dev/kvm not accessible"

Cause: forgot to run the udev step, or the runner doesn't support KVM
(e.g. `macos-latest`).
Fix: use `ubuntu-latest` and include the `Enable KVM group perms` step —
already present in `playback-test.yml`.

### System-image download hangs

Google's CDN. `reactivecircus/android-emulator-runner` retries automatically.
If it exhausts retries, re-dispatch the workflow.

### `NEW_RELIC_APP_TOKEN` reported as "(not set — NRVA will disable itself)"

The secret is missing or misnamed on the fork. Fix in the fork's
Settings → Secrets and variables → Actions.

### One leg silently produces zero events

Check the leg's artifact:

- `SUMMARY.txt` says `ok` but `viewId=(not-found)` → the app started but NRVA
  didn't. Look at `logcat.log` for NRVA init errors.
- `SUMMARY.txt` says `timeout` → the scenario never wrote its `SCENARIO_DONE`
  sentinel. Look at the per-scenario `auto-play-*.log` for what the app was
  doing when the 120-second poll ran out. Most common cause: sample-app hasn't
  been updated to support `SCENARIO_ID` (see
  [Sample-app contract](#sample-app-contract-what-the-app-must-do)).

### `ssai-mediatailor-lifecycle` fails on older NRVA versions

The `NRMediaTailorTracker` module was added in v4.2.0 or thereabouts. Older
tags don't ship the tracker or the sample activity. The build workflow
records this via `NRVA_HAS_MEDIATAILOR=false` on affected versions; the
scenario simply times out. To reach parity, drop it from `scenarios` in
`playback-defaults.json` when running the older-version sweep.

### A whole `(version × build)` job fails

Look at the `build · v<version>` job log. Common cause: an older tag's Gradle
setup references a Kotlin/AGP version that no longer resolves. Bump
`min_version` in `playback-defaults.json` to skip that release.

---

## File map

- `.github/workflows/playback-master.yml` — top-level orchestrator, cron +
  workflow_dispatch triggers, matrix fan-out, summary.
- `.github/workflows/playback-build.yml` — reusable child, builds one
  `NRSampleApp.apk` per NRVA version.
- `.github/workflows/playback-test.yml` — reusable child, runs one
  (version × leg) test job. This is where the AVD cache and KVM enabling live.
- `.github/workflows/playback-defaults.json` — the leg matrix + default
  scenario list. Single source of truth. Edit this to add or remove legs.
- `app/` — the sample app that runs playback scenarios. **Requires changes**
  to support `SCENARIO_ID` intent extras; see
  [Sample-app contract](#sample-app-contract-what-the-app-must-do).
- `.github/workflows/README.md` — this document. GitHub renders it
  automatically when browsing to `.github/workflows/` in the web UI.
