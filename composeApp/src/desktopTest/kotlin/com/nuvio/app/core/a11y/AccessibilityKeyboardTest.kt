package com.nuvio.app.core.a11y

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvioDesktopDragScroll
import com.nuvio.app.core.ui.PlatformBackDispatcher
import com.nuvio.app.features.player.PlayPauseControlButton
import com.nuvio.app.features.player.PlayerHeaderIconButton
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerOverlayScaffold
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

class AccessibilityKeyboardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun skipAndPlayRespondToEnterAndSpace() {
        var skipped = false
        var toggled = false
        compose.setContent {
            NuvioTheme {
                Column {
                    SkipIntroButton(
                        interval = SkipInterval(0.0, 30.0, "intro", "fixture"),
                        dismissed = false,
                        controlsVisible = true,
                        onSkip = { skipped = true },
                        onDismiss = {},
                    )
                    PlayPauseControlButton(
                        isPlaying = true,
                        isBuffering = false,
                        metrics = playerMetrics(),
                        onClick = { toggled = true },
                    )
                }
            }
        }
        val skip = compose.onNode(hasText("Skip the intro."))
        skip.requestFocus()
        skip.performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        assertTrue(skipped)
        val play = compose.onNode(hasContentDescription("Pause"))
        play.requestFocus()
        play.performKeyInput {
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
        }
        assertTrue(toggled)
    }

    @Test
    fun tabSkipsTheScrimAndEscapeDismisses() {
        var dismissed = false
        compose.setContent {
            NuvioTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                PlatformBackDispatcher.dispatch()
                            } else {
                                false
                            }
                        },
                ) {
                    PlayerOverlayScaffold(visible = true, onDismiss = { dismissed = true }) {
                        PlayPauseControlButton(
                            isPlaying = false,
                            isBuffering = false,
                            metrics = playerMetrics(),
                            onClick = {},
                        )
                        PlayerHeaderIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "Close player",
                            buttonSize = 48.dp,
                            iconSize = 24.dp,
                            onClick = {},
                        )
                    }
                }
            }
        }
        val scrim = compose.onNode(hasContentDescription("Dismiss the player controls."))
        scrim.requestFocus()
        assertFalse(scrim.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        val play = compose.onNode(hasContentDescription("Play"))
        play.requestFocus()
        assertEquals(true, play.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused))
        play.performKeyInput {
            keyDown(Key.Tab)
            keyUp(Key.Tab)
        }
        val close = compose.onNode(hasContentDescription("Close player"))
        assertEquals(true, close.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused))
        assertFalse(scrim.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        play.performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        assertTrue(dismissed)
    }

    @Test
    fun horizontalShelfScrollsWithArrowKeys() {
        var state: LazyListState? = null
        compose.setContent {
            NuvioTheme {
                val listState = rememberLazyListState()
                state = listState
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .width(180.dp)
                        .height(48.dp)
                        .nuvioDesktopDragScroll(listState)
                        .testTag("shelf"),
                ) {
                    items(20) { index ->
                        Text(
                            text = "Item $index",
                            modifier = Modifier
                                .width(80.dp)
                                .clickable {}
                                .testTag("item-$index"),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("item-0").requestFocus()
        compose.onNodeWithTag("item-0").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        compose.waitForIdle()
        val listState = checkNotNull(state)
        assertTrue(
            listState.firstVisibleItemScrollOffset > 0 || listState.firstVisibleItemIndex > 0,
            "Right arrow did not scroll the shelf",
        )
    }

    @Test
    fun posterCardIsAButtonAndActivatesWithEnter() {
        var clicked = false
        compose.setContent {
            NuvioTheme {
                NuvioPosterCard(
                    title = "Northwater",
                    imageUrl = null,
                    onClick = { clicked = true },
                )
            }
        }
        val card = compose.onNode(hasText("Northwater") and hasClickAction())
        assertEquals(Role.Button, card.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        card.requestFocus()
        card.performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        assertTrue(clicked)
    }
}

private fun playerMetrics() = PlayerLayoutMetrics(
    horizontalPadding = 24.dp,
    verticalPadding = 20.dp,
    titleSize = 24.sp,
    episodeInfoSize = 15.sp,
    metadataSize = 13.sp,
    centerGap = 88.dp,
    centerLift = 18.dp,
    sliderBottomOffset = 24.dp,
    sliderTouchHeight = 26.dp,
    sliderScaleY = 0.72f,
    timeSize = 14.sp,
    headerIconSize = 24.dp,
    sideButtonPadding = 14.dp,
    sideIconSize = 34.dp,
    playButtonPadding = 18.dp,
    playIconSize = 44.dp,
)
