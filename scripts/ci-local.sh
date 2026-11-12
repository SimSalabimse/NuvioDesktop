#!/usr/bin/env bash
# Local stand-in for the Linux compile and test work in .github/workflows.
# GitHub Actions may be blocked by billing. pr-template-check.yml is
# workflow_dispatch only until that trigger is restored (see docs/CI_LOCAL.md).
#
#   scripts/ci-local.sh <branch|pr/N> [--base <ref>]
#
# <ref> is a branch or "pr/<number>". origin is preferred over a local branch
# of the same name. The ref is checked out in a temporary git worktree, so the
# working copy you run from is never touched.
#
# Options:
#   --base <ref>   Base for the name and secrets scans (default origin/enhanced)
#   --no-fetch     Skip git fetch
#   --keep         Keep the worktree for inspection
#
# Environment:
#   NUVIO_CI_LOG_DIR   Log folder (default ~/agent-tools/ci-local)
#   NAME_DENYLIST      Comma or newline list for the name scan. When unset or
#                      blank, ~/.config/nuvio/name-denylist is read. A missing
#                      list fails the run. The list is never written to the
#                      repo or the log.
#   DEVELOPER_DIR      On macOS, defaults to the active Xcode. A beta Xcode
#                      is refused. This script never uses sudo.
#
# Exit status is 0 only when every required step passed. The last line is
# "CI-LOCAL PASS ref=... sha=..." or "CI-LOCAL FAIL ref=... failed: ...".
# Written for the macOS system bash 3.2 as well as newer bash.
set -o pipefail

usage() { sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; }

BASE="origin/enhanced"
FETCH=1
KEEP=0
REF=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --no-fetch) FETCH=0; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) REF="$1"; shift ;;
  esac
done
[[ -n "$REF" ]] || { usage >&2; exit 2; }

REPO="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)" || exit 2
export PYTHONPATH="${REPO}${PYTHONPATH:+:${PYTHONPATH}}"
LOG_DIR="${NUVIO_CI_LOG_DIR:-${HOME}/agent-tools/ci-local}"
mkdir -p "$LOG_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
SAFE_REF="$(printf '%s' "$REF" | tr -c 'A-Za-z0-9._-' '-')"
LOG="${LOG_DIR}/${SAFE_REF}-${STAMP}.log"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1

echo "Nuvio ci-local"
echo "log: ${LOG}"
echo "started: $(date '+%Y-%m-%d %H:%M:%S %Z')"

fail_line() {
  echo "CI-LOCAL FAIL ref=${REF} failed: $*"
  exit 1
}

if [[ "$FETCH" -eq 1 ]]; then
  git -C "$REPO" fetch --quiet --prune origin || fail_line "git fetch"
fi

if [[ "$REF" =~ ^pr/([0-9]+)$ ]]; then
  PR_NUMBER="${BASH_REMATCH[1]}"
  if [[ "$FETCH" -eq 1 ]]; then
    git -C "$REPO" fetch --quiet origin "pull/${PR_NUMBER}/head" || fail_line "fetch PR ${PR_NUMBER}"
    SHA="$(git -C "$REPO" rev-parse FETCH_HEAD)"
  elif git -C "$REPO" rev-parse --verify --quiet "refs/remotes/origin/pr/${PR_NUMBER}^{commit}" >/dev/null; then
    SHA="$(git -C "$REPO" rev-parse "refs/remotes/origin/pr/${PR_NUMBER}^{commit}")"
  else
    fail_line "cannot resolve pr/${PR_NUMBER} without fetch"
  fi
elif git -C "$REPO" rev-parse --verify --quiet "origin/${REF}^{commit}" >/dev/null; then
  SHA="$(git -C "$REPO" rev-parse "origin/${REF}^{commit}")"
elif git -C "$REPO" rev-parse --verify --quiet "${REF}^{commit}" >/dev/null; then
  SHA="$(git -C "$REPO" rev-parse "${REF}^{commit}")"
else
  fail_line "cannot resolve ref ${REF}"
fi

if ! git -C "$REPO" rev-parse --verify --quiet "${BASE}^{commit}" >/dev/null; then
  fail_line "cannot resolve base ${BASE}"
fi
BASE_SHA="$(git -C "$REPO" rev-parse "${BASE}^{commit}")"

echo "ref: ${REF}  sha: ${SHA}  base: ${BASE} ($(git -C "$REPO" rev-parse --short "$BASE_SHA"))"
case "$(uname -s)" in
  Darwin) HOST_OS="macOS" ;;
  Linux) HOST_OS="Linux" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_OS="Windows" ;;
  *) HOST_OS="$(uname -s)" ;;
esac
echo "host: ${HOST_OS}"
echo "java: $(java -version 2>&1 | head -n 1)"

WT=""
cleanup() {
  [[ -n "$WT" ]] || return 0
  if [[ "$KEEP" -eq 1 ]]; then
    echo "kept worktree: ${WT}"
  else
    git -C "$REPO" worktree remove --force "$WT" >/dev/null 2>&1 || rm -rf "$WT"
    git -C "$REPO" worktree prune >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
WT="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ci.XXXXXX")"
git -C "$REPO" worktree add --quiet --detach "$WT" "$SHA" || fail_line "worktree add"

RESULTS=""
FAILED=""
add_result() { RESULTS="${RESULTS}
  $1"; }
add_fail() { FAILED="${FAILED}${FAILED:+; }$1"; }

step() {
  name="$1"
  shift
  echo ""
  echo "==> ${name}"
  t0=$SECONDS
  if "$@"; then
    add_result "ok    ${name} ($((SECONDS - t0))s)"
  else
    add_result "FAIL  ${name} ($((SECONDS - t0))s)"
    add_fail "$name"
  fi
}
skip() {
  add_result "skip  $1: $2"
  echo ""
  echo "==> $1"
  echo "notice: skipped, $2"
}

refuse_beta_xcode() {
  dir="${DEVELOPER_DIR:-}"
  if [[ -z "$dir" ]]; then
    dir="$(xcode-select -p 2>/dev/null || true)"
  fi
  case "$dir" in
    *[Bb]eta*|*[Xx]code-beta*)
      echo "refusing a beta Xcode: ${dir}"
      return 1
      ;;
  esac
  ver="$(DEVELOPER_DIR="${dir}" xcodebuild -version 2>/dev/null || true)"
  case "$ver" in
    *[Bb]eta*)
      echo "refusing a beta Xcode"
      return 1
      ;;
  esac
  if [[ -n "$ver" ]]; then
    echo "$ver"
  fi
  return 0
}

step "scan self-tests" python3 -m unittest scripts.ci.test_scans
step "name denylist" python3 -m scripts.ci.name_denylist --repo "$REPO" --base "$BASE_SHA" --head "$SHA"
step "secrets scan" python3 -m scripts.ci.secrets_scan --repo "$REPO" --base "$BASE_SHA" --head "$SHA"

# GitHub's Ubuntu image provides g++ as c++. Prefer g++ when the c++ on PATH
# cannot see the C++ standard library (clang without libstdc++ headers).
prefer_libstdcxx() {
  if printf '%s\n' '#include <type_traits>' | c++ -std=c++17 -fsyntax-only -x c++ - >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v g++ >/dev/null 2>&1; then
    echo "c++ cannot compile the C++ standard library and g++ is not installed"
    return 1
  fi
  CXX_SHIM="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-cxx.XXXXXX")"
  ln -s "$(command -v g++)" "${CXX_SHIM}/c++"
  if command -v gcc >/dev/null 2>&1; then
    ln -s "$(command -v gcc)" "${CXX_SHIM}/cc"
  fi
  export PATH="${CXX_SHIM}:${PATH}"
  echo "using g++ as c++ because the default c++ cannot see the standard library"
}

desktop_job() {
  # gradle.properties asks for a 12g daemon and an 8g Kotlin daemon. The
  # command below keeps this run inside a smaller heap. --no-daemon makes
  # GRADLE_OPTS the build JVM. Release workflows pin Temurin 17; this run
  # uses the java on PATH.
  (
    cd "$WT" || exit 1
    CXX_SHIM=""
    if [[ "$HOST_OS" == "Linux" ]]; then
      prefer_libstdcxx || exit 1
    fi
    export GRADLE_OPTS="${GRADLE_OPTS:--Xmx4g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8}"
    export GRADLE_OPTS="${GRADLE_OPTS} -Dorg.gradle.daemon=false -Dorg.gradle.workers.max=2"
    if [[ "$HOST_OS" == "Linux" ]]; then
      export LIBGL_ALWAYS_SOFTWARE=1
    fi
    runner=()
    if [[ "$HOST_OS" == "Linux" ]] && command -v xvfb-run >/dev/null 2>&1; then
      runner=(xvfb-run -a)
    fi
    "${runner[@]}" ./gradlew \
      :composeApp:compileKotlinDesktop \
      :composeApp:desktopTest \
      --no-daemon \
      --no-configuration-cache \
      -Pkotlin.daemon.jvmargs=-Xmx3g \
      --stacktrace
    rc=$?
    python3 -m scripts.ci.junit_summary \
      "${WT}/composeApp/build/test-results/desktopTest" \
      --label "desktopTest" || true
    if [[ -n "${CXX_SHIM}" ]]; then
      rm -rf "${CXX_SHIM}"
    fi
    exit "$rc"
  )
}
step "desktop compile and test" desktop_job

if [[ "$HOST_OS" == "Linux" ]] && command -v dpkg-deb >/dev/null 2>&1; then
  step "linux deb tooling tests" bash "${WT}/scripts/linux/test-linux-deb-tools.sh"
else
  skip "linux deb tooling tests" "dpkg-deb is not on this host"
fi

# Packaging jobs from desktop-release.yml, android-release.yml, and
# ios-test-build.yml. Off-OS jobs are skipped. On the matching OS the script
# still refuses a beta Xcode and never calls sudo. Signed packages need
# release secrets that are not part of this compile-and-test gate.
if [[ "$HOST_OS" == "macOS" ]]; then
  step "macOS toolchain (stable Xcode, no sudo)" refuse_beta_xcode
  skip "macOS DMG packaging" "release signing secrets are not part of local CI"
  skip "iOS IPA packaging" "IPA packaging is manual; local CI does not call sudo"
else
  skip "macOS DMG packaging" "not macOS"
  skip "iOS IPA packaging" "not macOS"
fi
if [[ "$HOST_OS" == "Windows" ]]; then
  skip "Windows MSI packaging" "release signing secrets are not part of local CI"
else
  skip "Windows MSI packaging" "not Windows"
fi
skip "Linux release packages" "DEB, RPM, AppImage, and Flatpak stay on workflow_dispatch; Flatpak needs root and the packages need release secrets"
skip "Android release APK" "signed release needs the release keystore"

echo ""
echo "==> summary"
printf '%s\n' "$RESULTS"
echo "finished: $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "log: ${LOG}"
if [[ -z "$FAILED" ]]; then
  echo "CI-LOCAL PASS ref=${REF} sha=${SHA}"
  exit 0
fi
echo "CI-LOCAL FAIL ref=${REF} failed: ${FAILED}"
exit 1
