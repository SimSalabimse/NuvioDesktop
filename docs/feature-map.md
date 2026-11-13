# Desktop feature map

Detection is compared with NuvioMobile-Enhanced branch `cursor/batch12-build207-e33c` at `fa2477f87aeea196bd7ebafd10d9795f3c5af6ff` (PR #125). The two apps are separate repositories. Desktop does not compile the mobile module. The shared decision code is copied into `composeApp/src/commonMain/kotlin/com/nuvio/app/features/analysis`. The mobile download scheduler, platform audio analyser, and chip UI are not hosted here.

## Intro, recap, and credits detection

| Desktop | Mobile counterpart | Gap |
| --- | --- | --- |
| `features/player/skip/SkipIntroRepository.kt` `mergeSkipIntervalsByPriority`, `segmentCategory` | `SkipIntroRepository.mergeByPriority`, `segmentCategory` | Desktop dropped `movie-credits` and `post-credits` before this port. Movie credits now share the ending slot. Post-credits keep their own slot. First provider still wins a shared category. |
| `features/analysis/ClipDecodeGovernor.kt` `verifiedSegmentBounds`, `provenSharedRun`, `locatedRunInside` | same file | Ported. A shared run of at least 8 seconds and at least half the mark is kept and is not widened. A slice inside that run is the same run. A second run outside it is not stored. |
| `features/analysis/SiblingClipReference.kt` `locate`, `alignReferenceSegment`, `settleReferenceRead`, `mergeReferenceWrite` in `ReferenceClipPlan.kt` | `SiblingClipReference.readKnownReference` plus `alignReferenceSegment` | The keep/drop decision is ported. A credits-window miss does not clear a matched intro. `Partial(unverified-slice)` does not write an intro. Opening a sibling file, debrid, and the stream cache are not ported. |
| `features/analysis/FingerprintMatcher.kt` `RecapDetector`, `SeasonSegmentDetector` | same | Ported, including a spoken opening shared by two siblings. |
| `features/analysis/RecapMarkStatus.kt`, `AnalysisPart.kt`, `SegmentOutcomes.kt` | `RecapMarkStatus`, `MediaAnalysisStatus.AnalysisPart`, `MediaAnalysisScheduler.withSegmentOutcomes` | Ported. A short or empty opening print stores `not-checked-short-window` or `not-checked-no-print`, not a completed miss. A cancelled pass drops a stale `rejected-no-match`. The live job state and download chips are not ported. |
| `features/analysis/SkipLearning.kt` `matchReference`, `learnFromReferences` | `SkipLearning` | The match rules are ported, including the 8 second and half-window bar and the same-cut check. `recordMark`, `importMark`, and `teachSeason` are not ported: they write `MediaAnalysisStore`. |
| `features/analysis/SeasonFingerprintPass.kt`, `PreviewDetector.kt`, `SilenceEdges.kt`, `SegmentDecision.kt` | same | Ported so the season pass and the dialog-overlay gate compile with the detectors. |
| `features/player/SameCut.kt` | `PlayerMediaAnalysis.sameCut` | Ported. The 30 second cut tolerance. |
| No desktop file | `MediaAnalysisScheduler`, `MediaAnalyzerPlatform`, `MediaAnalysisStore`, `AnalysisStatusUi` | Not ported. See the design note below. |
| `features/player/skip/SkipIntroRepository.kt` remote providers (TheIntroDB, IntroDB, AniSkip, AnimeSkip) | same, plus on-device intervals from `PlayerMediaAnalysis` | Remote lookup already existed. On-device intervals are not applied during playback because nothing on desktop writes an analysis file. |
| `features/downloads/DownloadWatchState.kt` `downloadSkipSourceLine` | season sheet public line and own line, recap chip sentence | Desktop shows this account's marks only. It does not say Not checked, No recap found, or Public. UI-only; not changed in this port. |

## Not hosted

A desktop audio analyser would need a platform decoder (the mobile one is an `expect` over FFmpeg), a file store, and a queue tied to finished downloads. Wiring that through the player is a separate host, not a change to these decision functions. Playback continues to use the remote skip providers, now including movie credits and post-credits that the merge used to drop.

## Design left for a later host

Sharing the mobile Gradle module is not practical: the repositories are separate, and packaging, signing, and release workflows stay unchanged. The copied package is the pure decision layer. The host below is the piece that would make those decisions run on a desktop download.

What: a desktop `MediaAnalyzerPlatform` that fingerprints a finished download, a `MediaAnalysisStore` that keeps fingerprints after the file is deleted, and a scheduler that runs intro, credits, and recap passes and writes `withSegmentOutcomes`. Playback would then read those stored intervals the way mobile `PlayerMediaAnalysis` does.

Files: new desktop actuals beside `features/analysis` (`MediaAnalyzerPlatform`, `MediaAnalysisStore`, a scheduler that calls the functions already in `SegmentOutcomes.kt` and `SiblingClipReference.kt`). `SkipIntroRepository` would gain a local provider that reads the store. `SkipLearning.recordMark` would write the store.

Risks: the decoder is a new native dependency, the store is a new on-disk format, and a wrong same-cut check would apply another cut's times. None of that is in this change. The decision functions are callable and tested without that host.

## Manual TheIntroDB submit

The desktop player submits intro, recap, credits (shown as Outro), and preview for movies and TV, including a segment that starts at the beginning or runs to the end. Video duration is sent only inside the 5 minute to 6 hour guard. Catalogue reads and submits use API v4. A key is checked with POST /map. The v4 docs do not describe a user-stats route.
