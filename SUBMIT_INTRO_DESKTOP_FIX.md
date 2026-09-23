# Desktop Submit Intro Button Fix

## Problem
Users reported that the submit intro/flag button visible in the iOS (NuvioMobile-Enhanced) player was not appearing in the Desktop player.

## Root Cause
The Desktop player **does have** the submit intro button implemented in its native HTML/JS controls (`composeApp/src/desktopMain/resources/player-ui/controls.html` line 138), but it was hidden by default due to a configuration requirement.

The button visibility is controlled by the following conditions (from `PlayerScreenRuntimeUi.kt` lines 369-372):

```kotlin
showSubmitIntro = isSeries &&
    playerSettingsUiState.introSubmitEnabled &&
    playerSettingsUiState.introDbApiKey.isNotBlank() &&
    !activeSubmitIntroImdbId().isNullOrBlank()
```

### Required Conditions:
1. **`isSeries`**: Must be watching a TV series (not a movie)
2. **`introSubmitEnabled`**: Feature toggle must be enabled (was defaulting to `false`)
3. **`introDbApiKey.isNotBlank()`**: IntroDb API key must be configured
4. **Valid IMDB ID**: The content must have a valid IMDB ID (starts with "tt")

## Solution
Changed the default value of `introSubmitEnabled` from `false` to `true` in:
- `PlayerSettingsRepository.kt` - `AutoPlayPlayerSettingsState` data class (line 75)
- `PlayerSettingsRepository.kt` - private variable initialization (line 147)
- `PlayerSettingsRepository.kt` - `clearSettingsForProfile()` method (line 223)
- `PlayerSettingsRepository.kt` - `loadAllSettings()` method (line 363)

### Impact:
- **Before**: Users needed to manually enable "Submit Intro Timestamps" toggle in Settings > Playback even after configuring an API key
- **After**: The feature is enabled by default. As soon as users have an API key configured (either directly on Desktop or synced from iOS/Mobile), the button will appear automatically

### UX Improvement:
- Better parity with iOS/Mobile behavior
- Reduces friction - users only need to configure the API key, not also find and enable a separate toggle
- Users without an API key won't see the button anyway (due to the `introDbApiKey.isNotBlank()` check)

## Verification Steps for Simen:

1. **Prerequisites**:
   - Must be watching a TV series (the button doesn't appear for movies)
   - The series must have a valid IMDB ID (most series from standard metadata providers do)

2. **Setup**:
   - Go to Settings > Playback
   - Ensure "Submit Intro Timestamps" toggle is ON (now enabled by default)
   - Configure your IntroDb API key (Settings > Playback > "IntroDb API Key")
   - Or configure TheIntroDb API key (Settings > Playback > "TheIntroDb API Key")

3. **Testing**:
   - Play any TV series episode
   - Look for the flag icon button in the top-right header of the player controls
   - Click it to open the submit intro dialog
   - You can select segment type (Intro/Recap/Outro/Preview), capture timestamps, and submit

## Technical Details

### Desktop Implementation:
- **HTML**: `composeApp/src/desktopMain/resources/player-ui/controls.html` line 138
- **JavaScript**: `composeApp/src/desktopMain/resources/player-ui/controls.js` lines 42, 144-159, 1888-1910, 2283, 2678-2680
- **CSS**: Button styling in `controls.css`
- **Kotlin Bridge**: `NativePlayerController.kt` lines 1207, 1269, 1301-1317, 1490, 1526-1536, 1580

### Visibility Control:
The button is shown/hidden using JavaScript:
```javascript
setVisible(submitIntroButton, Boolean(state.showSubmitIntro));
```

Where `state.showSubmitIntro` comes from the Kotlin `PlayerControlsState` which is computed in `PlayerScreenRuntimeUi.kt`.

### API Keys:
- **IntroDb**: Community-maintained intro/outro timestamp database
- **TheIntroDb**: Alternative timestamp database
- Both are supported; at least one API key is required for the feature to work

## Files Modified:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt` (4 locations)

## No Changes Needed:
The Desktop player implementation was already complete and correct. Only the default configuration value needed adjustment for better UX parity with Mobile.
