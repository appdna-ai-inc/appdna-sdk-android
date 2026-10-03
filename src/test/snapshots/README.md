# Android Visual Goldens

Committed PNG snapshots produced by [Roborazzi](https://github.com/takahirom/roborazzi) (chosen over Paparazzi) and reviewed during PR.

## What lives here

PNG files written by `captureRoboImage(file = …)` calls inside Robolectric-driven Compose snapshot tests. Each PNG is the reference output a Compose renderer must match; PR diff tools render them inline so reviewers see pixel changes during code review.

Roborazzi runs **on the JVM** via Robolectric — no device, no emulator, no GPU. The snapshot suite is fast enough for PR-time CI.

## When to add a golden

Add a new committed snapshot in the **same PR** that introduces or modifies a Compose renderer surface., the initial set is 12 surfaces (mirrors iOS):

1. Paywall hero (light)
2. Paywall hero (dark)
3. Onboarding welcome step
4. Onboarding form step (text + select inputs)
5. Survey single-choice
6. Survey CSAT (1–5 scale)
7. In-app message — banner
8. In-app message — modal
9. In-app message — fullscreen
10. In-app message — tooltip
11. Push notification preview
12. Paywall plan-select pressed state (also covers error banner via shared chrome)

## How to (re)record

```bash
cd packages/appdna-sdk-android
# Re-record all goldens:
./gradlew recordRoborazziDebug
git add src/test/snapshots/
```

## CI

Runs in `.github/workflows/sdk-visual-regression.yml` (job `android-visual`). Currently gated `if: ${{ false }}` until the first batch of goldens lands. Verification command:

```bash
./gradlew verifyRoborazziDebug
```

Fails with a side-by-side image diff if any PNG drifts.
