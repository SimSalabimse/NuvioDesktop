package com.nuvio.app.core.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.CatalogStatusChip
import com.nuvio.app.features.details.components.CatalogStatusLines
import com.nuvio.app.features.details.components.IntroDbStatusBlock
import com.nuvio.app.features.player.PlayPauseControlButton
import com.nuvio.app.features.player.PlayerHeaderIconButton
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerOverlayScaffold
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroButton
import kotlin.test.Test
import org.junit.Rule

/**
 * Counts interactive nodes that expose neither text nor a content description.
 * The printed lines are the measurement. The assertions are the after contract.
 */
class AccessibilitySemanticsHarnessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun countUnlabeledInteractiveNodes() {
        val metrics = PlayerLayoutMetrics(
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
        compose.setContent {
            NuvioTheme {
                Column(Modifier.testTag("a11y-root")) {
                    SkipIntroButton(
                        interval = SkipInterval(0.0, 30.0, "intro", "fixture"),
                        dismissed = false,
                        controlsVisible = true,
                        onSkip = {},
                        onDismiss = {},
                    )
                    PlayPauseControlButton(
                        isPlaying = true,
                        isBuffering = false,
                        metrics = metrics,
                        onClick = {},
                    )
                    PlayPauseControlButton(
                        isPlaying = false,
                        isBuffering = true,
                        metrics = metrics,
                        onClick = {},
                    )
                    PlayerHeaderIconButton(
                        icon = Icons.Rounded.Public,
                        contentDescription = "Close player",
                        buttonSize = 48.dp,
                        iconSize = 24.dp,
                        onClick = {},
                    )
                    CatalogStatusLines(
                        publicLine = "Public · Not checked",
                        ownLine = "Yours · Not submitted",
                    )
                    CatalogStatusChip(icon = Icons.Rounded.Public, text = "Public · Intro, Credits")
                    IntroDbStatusBlock(parentMetaId = "fixture-show", season = 1, episode = 1)
                    PlayerOverlayScaffold(visible = true, onDismiss = {}) {
                        Text("Player", modifier = Modifier.testTag("player-body"))
                    }
                }
            }
        }
        val interactive = compose.onNodeWithTag("a11y-root").fetchSemanticsNode()
        val unlabeled = interactive.unlabeledInteractiveCount()
        val fragments = interactive.fragmentCount()
        val missingRoles = interactive.missingRoleCount()
        val images = interactive.imageCount()
        println("metric=unlabeled_interactive_nodes count=$unlabeled")
        println("metric=interactive_missing_role count=$missingRoles")
        println("metric=status_fragments count=$fragments")
        println("metric=exposed_images count=$images")
        interactive.dumpClicks()
        assertEquals(0, unlabeled)
        assertEquals(0, missingRoles)
        assertEquals(0, fragments)
        assertEquals(0, images)
        assertSpoken("Skip the intro.")
        assertSpoken("The public catalogue has not been checked.", atLeast = 2)
        assertSpoken("You have not submitted skip marks.", atLeast = 2)
        assertSpoken("The public catalogue lists Intro, Credits.")
        assertSpokenDescription("Buffering. Play")
        assertSpokenDescription("Dismiss the player controls.")
        val body = compose.onNodeWithTag("player-body").fetchSemanticsNode()
        assertFalse(body.config.contains(SemanticsActions.OnClick))
    }

    private fun assertSpoken(text: String, atLeast: Int = 1) {
        val count = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size
        assertTrue(count >= atLeast, "$text count $count")
    }

    private fun assertSpokenDescription(text: String) {
        val count = compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().size
        assertTrue(count >= 1, "$text count $count")
    }
}

private fun androidx.compose.ui.semantics.SemanticsNode.unlabeledInteractiveCount(): Int {
    val self = if (isUnlabeledInteractive()) 1 else 0
    return self + children.sumOf { it.unlabeledInteractiveCount() }
}

private fun androidx.compose.ui.semantics.SemanticsNode.fragmentCount(): Int {
    val text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(separator = "") { it.text }
    val self = if ("·" in text) 1 else 0
    return self + children.sumOf { it.fragmentCount() }
}

private fun androidx.compose.ui.semantics.SemanticsNode.imageCount(): Int {
    val self = if (config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Image) 1 else 0
    return self + children.sumOf { it.imageCount() }
}

private fun androidx.compose.ui.semantics.SemanticsNode.missingRoleCount(): Int {
    val self = if (config.contains(SemanticsActions.OnClick) && config.getOrNull(SemanticsProperties.Role) == null) 1 else 0
    return self + children.sumOf { it.missingRoleCount() }
}

private fun androidx.compose.ui.semantics.SemanticsNode.dumpClicks() {
    if (config.contains(SemanticsActions.OnClick)) {
        val text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(separator = "") { it.text }
        val description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(separator = "")
        val role = config.getOrNull(SemanticsProperties.Role)
        println("click text=${text.replace('\n', ' ')} desc=$description role=$role")
    }
    children.forEach { it.dumpClicks() }
}

private fun androidx.compose.ui.semantics.SemanticsNode.isUnlabeledInteractive(): Boolean {
    if (!config.contains(SemanticsActions.OnClick)) return false
    val text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(separator = "") { it.text }
    val description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(separator = "")
    return text.isBlank() && description.isBlank()
}
