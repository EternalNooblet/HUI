# Plan: GitHub Release Pipeline for the hui-phone Android APK

Generated 2026-11-03 from a live inspection of `~/projects/hui/hui-phone/`.
Repo root: `~/projects/hui/` (github.com:EternalNooblet/HUI, branch `main`).

## Verified current state

| Aspect | What exists (checked, not assumed) |
|---|---|
| Existing release workflow | `.github/workflows/release.yml` — tag push `v*`, `runs-on: ubuntu-24.04`, `timeout-minutes: 15`, `permissions: contents: write`. Builds 5 Go CLI binaries (linux/amd64, linux/arm64, windows/amd64, darwin/arm64, darwin/amd64) and publishes them via `gh release create` in one job. **No Android job.** |
| Latest release | `v0.1.9` (`github.com/EternalNooblet/HUI/releases/tag/v0.1.9`) — 5 Go assets, `draft: false`. Tag pushes today already trigger the Go pipeline. |
| Android project | `hui-phone/companion-app/` — Gradle wrapper 8.9, AGP 8.7.2, Kotlin 1.9.25 (kapt), `compileSdk/targetSdk 34`, `minSdk 27`, JDK 17 locally (`/usr/lib/jvm/java-17-openjdk`), Android SDK at `~/Android/Sdk` (platform android-34, build-tools 34.0.0 with `apksigner`/`zipalign`). |
| Signing | **None in Gradle.** `app/build.gradle.kts` `buildTypes.release` sets minify + R8 but no `signingConfig`, so `assembleRelease` emits an *unsigned* APK. The README's manual flow signs it afterwards with `apksigner` against a debug keystore at `/tmp/debug.keystore` (which does not even exist locally anymore — only `~/.android/debug.keystore` does, and it is not a release key). No `.keystore` file is committed (gitignored) and no signing-related GitHub secret is known to exist. |
| Versioning | `versionCode = 1` (static) and `versionName = "1.0.0-<random-codename>-<epoch>"` generated at configuration time with `Math.random()`. Every build — local or CI — gets a different name, and `versionCode` never increments, so a second build cannot upgrade-install over the first reliably. |
| Secrets/config | `local.properties` (gitignored) points at the local SDK only. Hardcoded `SERVER_URL = wss://10.0.0.126:8081` in `defaultConfig`. No NDK/Cmake/externalNativeBuild — CI does not need C++ toolchains. No R8/ProGuard issue expected: `proguard-rules.pro` already exists. |
| No Java tests | There is no unit-test task with sources in `app/src/` beyond the default `assembleUnitTest` scaffolding; CI verification is build + sign + zipalign implicitly. |

## Key decision to make before coding

**One tag, one release, two asset families** is the right shape. The existing workflow
already fires on `v*` and owns the release. Add a second job `apk` to the *same*
workflow so a single `v0.1.10` (or next) tag produces one GitHub Release containing:

- the existing 5 Go binaries (unchanged), plus
- `hui-phone-v0.1.10.apk` (signed, installable) and
- `hui-phone-v0.1.10.aab` (Play-Store-ready bundle), plus
- `SHA256SUMS.txt` covering all assets.

Rationale: `gh release create` from two jobs races (who creates the release), so the
second job must use `gh release upload` against the release the first job creates —
or, if jobs run in parallel, add `needs: [release]` to the apk job. Sequential is
slower but simpler and debuggable; parallel + `needs` is the end state.

Alternatives considered and rejected:
- *Separate workflow* (`android-release.yml` on `v*-android`): clean separation, but
  two tags / two releases for one version — confusing for users, easy to forget one.
- *Per-commit CI* (every push builds a test APK): useful later, but out of scope —
  we need the tag-push release path first.

## Phased plan

### Phase 0 — Prep (local, 30 min, no secrets needed yet)

1. Confirm CI runner can reproduce the current (unsigned) build:
   `cd hui-phone/companion-app && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=~/Android/Sdk ./gradlew assembleRelease`
   Expect `app/build/outputs/apk/release/app-release-unsigned.apk`. This is the
   baseline the CI job must reproduce before signing is layered on.
2. Decide the signing story (see Phase 1): one-time generation of a release keystore
   with real passwords is required. Until then, everything signs with the debug
   keystore *only for the smoke test* — never ship a debug-signed APK as a release.

### Phase 1 — Make the build release-ready (Gradle changes)

1. **Deterministic, tag-derived versioning.** Replace the random codename versionName
   in `app/build.gradle.kts`:
   - `versionName`: read `VERSION_NAME` from an env var/property; default to the git
     tag (`git describe --tags --abbrev=0`) or `1.0.0-dev` locally.
   - `versionCode`: monotonically increasing — e.g. `GITHUB_RUN_NUMBER` when run in
     Actions, or `System.currentTimeMillis()/1000` as the local fallback (already
     used for BUILD_NUMBER, but as a long — `versionCode` must be an int, so cast
     carefully).
   - Keep `BUILD_CODENAME` / `BUILD_NUMBER` BuildConfig fields for in-app display,
     but source them from the same tag/commit, not `Math.random()`.
2. **Signing config.** Add a `signingConfigs.create("release")` reading from
   properties (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)
   passed via `-P` flags / env in CI, and set `buildTypes.release.signingConfig`.
   Fall back to the debug keystore *only* when no store properties are present so
   local `assembleRelease` keeps working for quick smoke builds. Verify with
   `./gradlew signingReport` locally before touching CI.
3. **Align outputs.** Confirm `assembleRelease` emits the signed
   `app-release.apk` (not `-unsigned.apk`) once Phase 1.2 lands — the workflow and
   README must reference the right filename. Also build `bundleRelease` → `.aab`.

### Phase 2 — Workflow: extend `.github/workflows/release.yml`

Keep the existing `release` job untouched (it works; per the skill's schema gotchas,
only touch what is provably correct). Add:

```yaml
  apk:
    needs: release                 # publish after the Go release exists
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    permissions:
      contents: write
    defaults:
      run:
        working-directory: hui-phone/companion-app
    steps:
      - uses: actions/checkout@v4
        with: { git-ref: ${{ github.ref }} }

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }

      - name: Install Android SDK components
        run: |
          yes | sdkmanager --licenses || true
          sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

      - name: Restore release keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          test -n "$KEYSTORE_BASE64" || { echo "::error::ANDROID_KEYSTORE_BASE64 secret missing"; exit 1; }
          echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.keystore"

      - name: Build signed APK + AAB
        env:
          VERSION_NAME: ${{ github.ref_name }}   # e.g. v0.1.10
          VERSION_CODE: ${{ github.run_number }}
        run: |
          ./gradlew assembleRelease bundleRelease \
            -Pandroid.injected.signing.store.file="$RUNNER_TEMP/release.keystore" \
            -Pandroid.injected.signing.store.password="${{ secrets.ANDROID_KEYSTORE_PASSWORD }}" \
            -Pandroid.injected.signing.key.alias="${{ secrets.ANDROID_KEY_ALIAS }}" \
            -Pandroid.injected.signing.key.password="${{ secrets.ANDROID_KEY_PASSWORD }}"

      - name: Verify outputs
        run: |
          ls -lh app/build/outputs/apk/release/*.apk app/build/outputs/bundle/release/*.aab
          "$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose app/build/outputs/apk/release/*.apk
          sha256sum app/build/outputs/apk/release/*.apk app/build/outputs/bundle/release/*.aab > SHA256SUMS.txt
          cat SHA256SUMS.txt

      - name: Publish Android assets to the existing release
        env:
          GH_TOKEN: ${{ github.token }}
          TAG: ${{ github.ref }}
        run: |
          NAME="${TAG#refs/tags/}"
          # Correct asset filenames to the version tag so users can tell releases apart.
          cp app/build/outputs/apk/release/app-release.apk "hui-phone-${NAME}.apk"
          cp app/build/outputs/bundle/release/app-release.aab "hui-phone-${NAME}.aab"
          gh release upload "$NAME" "hui-phone-${NAME}.apk" "hui-phone-${NAME}.aab" SHA256SUMS.txt --clobber
```

Schema rules (from hard lessons in the GitHub release pipeline skill — a 0-job run
means schema error, not a step failure):
- `timeout-minutes:` (not bare `timeout:`), every step has `run:` or `uses:`,
  job `permissions` accepts `contents: write` (`release:` is **not** a valid key).
- `GH_TOKEN: ${{ github.token }}` — bare `GITHUB_TOKEN` is rejected by `gh`.
- `${TAG#refs/tags/}` — `github.ref` on a tag push is the full `refs/tags/v*`.
- Secrets are referenced as `${{ secrets.* }}` inside env blocks, never interpolated
  into `run:` scripts.

### Phase 3 — Secrets (one-time, before the first real tag)

1. Generate the release keystore once, locally, with real passwords
   (`keytool -genkeypair -keystore ~/.keystore/hui-release.keystore -alias hui-release -keyalg RSA -keysize 2048 -validity 10000`).
   Back it up as an encrypted offline copy — losing it means you can never publish an
   update to an already-installed release-signed app.
2. Add GitHub secrets (`gh secret set` or repo Settings → Secrets):
   - `ANDROID_KEYSTORE_BASE64` = `base64 -w0 hui-release.keystore`
   - `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
3. A missing secret must fail the run loudly (the `test -n` guard above) — silently
   publishing an unsigned APK is worse than not publishing at all.

### Phase 4 — Test before the real tag

1. **Local full rehearsal:** run Phase 1's gradle changes locally, rebuild with the
   release keystore, `apksigner verify` the output, install it with
   `adb install -r` on the test phone, confirm the app launches, connects to the
   laptop WSS server, and `adb logcat -s CompanionService:V Transport:V` is clean.
2. **CI path on a throwaway tag:** if secrets are in place, push a real tag
   (e.g. `v0.1.10`) and watch the run. If not, use `gh workflow run release.yml --ref main`
   with a `workflow_dispatch` trigger added temporarily to exercise the exact same
   steps without burning a version tag.
3. **Verify the result, don't trust the run flag:** per the skill's verify-done rule,
   `gh api repos/EternalNooblet/HUI/releases --jq '.[] | select(.tag_name=="v0.1.10") | .assets[].name'`
   must list all 8 assets (5 Go + APK + AAB + SHA256SUMS.txt), `draft: false`.
   Download the APK from the release page and `apksigner verify` *that* file too —
   this catches the class of bug where CI built correctly but uploaded a stale artifact.
4. **Regression:** the next Go-only release (or the same tag) must still show all 5
   Go binaries. If both jobs share the release, confirm nothing was clobbered.

### Phase 5 — Docs & cleanup

1. Update `hui-phone/README.md` Build & Deploy section: replace the manual
   `apksigner` flow with "download from the GitHub release page" and document how to
   cut a release (`git tag vX.Y.Z && git push origin vX.Y.Z`).
2. Update the workflow header comment that currently says "builds static binaries
   for linux/x86_64…" to mention the APK/AAB job.
3. Remove the now-dead `app-release-signed.apk` sitting in the repo directory
   (it is gitignored anyway, but it is a distribution hazard on disk).

## Risks & open questions

- **VersionCode monotonicity:** Play Store and Android both refuse downgrade installs.
  `GITHUB_RUN_NUMBER` resets per workflow re-runs and is repo-global (not per-tag),
  so it is monotonic for this workflow but must not be shared with another workflow
  that also bumps it. Timestamp-based versionCode is simpler and self-healing —
  pick one.
- **`versionName` vs asset names:** keep `1.0.0-<codename>` for in-app display only if
  you like it; the *tag* is the user-visible version. Do not let a random codename
  into a store listing.
- **Hardcoded `SERVER_URL` (`10.0.0.126`):** a release APK built for anyone else
  still points at your laptop. Out of scope for this pipeline, but flag it before
  calling the APK "public".
- **Release asset size:** TFLite model + CameraX make the APK large; confirm
  `dist` upload limits are fine (25 MB+ is routine for GitHub Releases — no action).
- **R8/minify + reflection:** already enabled locally; KSP/kapt-generated Room DB
  classes are covered by default rules. If the release build crashes where debug
  does not, that asymmetry is the first suspect.

## Done checklist

- [x] Phase 0 baseline build reproduced locally
- [x] Phase 1 deterministic versioning + signing config (local `assembleRelease` → signed APK) — verified 2026-11-03: signed APK + AAB, apksigner v2 OK, versionCode/versionName override OK
- [x] Phase 2 `apk` job in `release.yml` (validate YAML, re-simulate shell steps locally first) — YAML OK, shell snippets simulated OK
- [x] Phase 3 keystore + 4 secrets set (gh secret list: all 4 present 2026-11-03); offline backup: user to confirm
- [~] Phase 4 manual workflow_dispatch run 41740692551 GREEN (release job fixed for hui-cli/ layout): APK 22M + AAB 24M built, apksigner v2 OK with real keystore. REMAINING: real tag push to test publish/upload path + downloaded-APK verify + install
- [x] Phase 5 README + workflow comments updated; Go assets still intact (Go job untouched except publish gated on tag)
