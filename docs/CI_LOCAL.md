# Local CI

GitHub Actions may be blocked by billing on this private repo. The workflows under `.github/workflows` are kept. `pr-template-check.yml` no longer runs on `pull_request`. Its only trigger is `workflow_dispatch`. Put the `pull_request` trigger back to turn that check on again:

```yaml
on:
  pull_request:
    types: [opened, edited, synchronize, reopened]
```

The other root workflows were already `workflow_dispatch`, `schedule`, `issues`, `release`, or `workflow_call`. They were not changed. Workflows under `vendor/` belong to the vendored player and are not run by this repository's Actions.

`scripts/ci-local.sh` runs the Linux compile and unit-test work on a branch or pull request:

```sh
scripts/ci-local.sh cursor/my-branch
scripts/ci-local.sh pr/32
scripts/ci-local.sh enhanced --base origin/enhanced
```

| Actions job | Local step |
| --- | --- |
| Desktop compile and Kotlin tests (Ubuntu; the same tasks as a desktop PR check) | `:composeApp:compileKotlinDesktop` and `:composeApp:desktopTest` |
| Linux DEB tooling | `scripts/linux/test-linux-deb-tools.sh` when `dpkg-deb` is present |
| Name and secret guards | name denylist, secrets scan of the diff against the base, scanner self-tests |
| macOS DMG (`desktop-release.yml`) | SKIPPED off macOS. On macOS, stable Xcode only, never Xcode-beta, never sudo |
| iOS IPA (`ios-test-build.yml`, `android-release.yml`) | SKIPPED off macOS. Same Xcode rules, never sudo |
| Windows MSI (`desktop-release.yml`) | SKIPPED off Windows |
| Linux DEB, RPM, AppImage, Flatpak | SKIPPED here. They are release packages: Flatpak needs root, and the packages need release secrets |
| Android signed APK | SKIPPED. The release job needs the release keystore |

The ref is checked out in a temporary git worktree, so the working copy is untouched. The worktree is deleted at the end (`--keep` keeps it). `origin/<branch>` is used when it exists; otherwise the local branch is used.

Each run writes `~/agent-tools/ci-local/<ref>-<timestamp>.log` (override with `NUVIO_CI_LOG_DIR`) and prints the path. The last line is `CI-LOCAL PASS ref=… sha=…` or `CI-LOCAL FAIL ref=… failed: …`, and the exit status is non-zero on any failure.

Rules the script enforces:

- Release workflows pin Temurin 17. This script uses the `java` on PATH.
- On Linux, desktop compile uses `g++` when the `c++` on PATH cannot see the C++ standard library. The player bridge is compiled with that compiler. The script does not use sudo to install it.
- If `local.properties` is missing, the script creates an empty one in the worktree. Gradle 9 rejects that optional input when the path is set and the file is absent. The empty file is not committed and contains no secrets.
- `DEVELOPER_DIR` on macOS must not point at a beta Xcode. The script never runs `sudo`.
- The name scan reads `NAME_DENYLIST` from the environment or `~/.config/nuvio/name-denylist`. A missing list fails the run instead of skipping. The list never goes into the repo or the log.
- The secrets scan reads only the text added since the merge base with `--base` (default `origin/enhanced`). It reports the kind and the path, not the matched text.

## Merging

A pull request into `enhanced` needs a `CI-LOCAL PASS` run of its head, including the name and secrets scans. Paste the log path and the PASS line into the PR body. Do not merge into `Dev`.
