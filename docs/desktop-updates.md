# Desktop updates

The desktop updater reads one public address, with no token and no API key:

`https://api.github.com/repos/SimSalabimse/NuvioDesktop/releases?per_page=100`

That is the GitHub releases list for this fork. The request sends `Accept` and `User-Agent` only.

It does not read upstream `NuvioMedia/NuvioDesktop`. Upstream tags stay on a three-part line (`0.1.34`). This fork adds a fourth part (`0.1.30.3-alpha`). Comparing those lines as ordinary numbers treats `34` as greater than `30` and offers the older upstream build. The app no longer makes that request.

Mobile publishes a sideload catalog, `store.json`, from its own GitHub releases. Desktop does not use that file (it lists phone builds). Desktop uses the same idea: the fork's own releases are the feed. There is no separate desktop feed file. If this repository has no releases, no tags, or no installer for this computer, the check reports that the app is already current and does not show an error.

## Which tag counts as newer

A remote tag is offered only when every part below is true.

1. It parses. The form is at least three numeric dot-parts, then any further numeric parts, then an optional `-` suffix such as `-alpha` or `-submit-step1`, then optional `+` build metadata which is ignored. A suffix that does not fit is skipped. Parsing does not throw.
2. It has at least as many numeric parts as the installed version. `0.1.34` (three parts) is a different line from `0.1.30.3-alpha` (four parts) and is never an update.
3. The numeric parts are strictly greater, compared from the left. When the numbers are equal, a build with no suffix is greater than `-alpha` of those same numbers, and a longer suffix is greater than the shorter suffix it starts with (`-alpha.2` is greater than `-alpha`). An equal tag is not an update.
4. The file URL is an `https` download on `github.com/SimSalabimse/NuvioDesktop/releases/download/`. Any other host, including upstream, is ignored.

Worked examples:

- `0.1.34` is not an update for `0.1.30.3-alpha.1-submit-step1`.
- `0.1.30.4` is an update for `0.1.30.3-alpha`.
- `0.1.30.3-alpha` compared with itself is not an update.

The banner can appear for a release build when a tag passes that rule. Download starts only from the Update button. The installer runs only from the Install button. Launch does not download or replace the installed app.

## Integration builds

Gradle property: `-Pnuvio.updateChannel=release` or `-Pnuvio.updateChannel=integration`. The same names are read from `NUVIO_UPDATE_CHANNEL`. The default when the property is unset is `release` on the `enhanced` branch and `integration` on every other branch, including pull-request branches.

`integration` does not contact the feed and does not prompt. Settings still has Check for updates. On these builds that action reports the current copy and does not look for a download.

`packageReleaseDmg` packages the app that was compiled with this channel. A release DMG produced from a pull-request branch, with the property left unset, is an integration build and does not prompt.
