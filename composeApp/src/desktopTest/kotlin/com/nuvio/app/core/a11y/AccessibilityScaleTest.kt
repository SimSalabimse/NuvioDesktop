package com.nuvio.app.core.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.AppIconSettingsState
import com.nuvio.app.features.settings.AppLanguage
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.appearanceSettingsContent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccessibilityScaleTest {
    @AfterTest
    fun resetMotion() {
        ReducedMotion.resetForTest(false)
    }

    @Test
    fun desktopThemeKeepsTheIncomingFontScale() {
        val normal = fontScaleInsideTheme(Density(1f, fontScale = 1f))
        val large = fontScaleInsideTheme(Density(1f, fontScale = 1.5f))
        assertEquals(1.08f, normal, 0.001f)
        assertEquals(1.62f, large, 0.001f)
    }

    @Test
    fun largeTextGivesTitlesASecondLine() {
        val longTitle = "The Extraordinary Adventures of a Very Long Programme Title"
        val normal = titleHeights(Density(1f, fontScale = 1f), longTitle)
        val large = titleHeights(Density(1f, fontScale = 1.5f), longTitle)
        assertTrue(normal.second < normal.first * 1.4f, "Normal title grew to ${normal.second}")
        assertTrue(large.second > large.first * 1.4f, "Large title stayed one line at ${large.second}")
    }

    @Test
    fun reduceMotionSettingIsOnTheAppearancePage() {
        ReducedMotion.resetForTest(false)
        DesktopComposeUiTest(
            width = 900,
            height = 1400,
            density = Density(1f),
            useStandardTestDispatcherForComposition = false,
        ).runTest {
            setContent {
                NuvioTheme {
                    LazyColumn(Modifier.fillMaxSize()) {
                        appearanceSettingsContent(
                            isTablet = true,
                            selectedTheme = AppTheme.WHITE,
                            onThemeSelected = {},
                            amoledEnabled = false,
                            onAmoledToggle = {},
                            liquidGlassNativeTabBarSupported = false,
                            liquidGlassNativeTabBarEnabled = false,
                            onLiquidGlassNativeTabBarToggle = {},
                            appIconState = AppIconSettingsState(),
                            onAppIconSelected = {},
                            onAppIconFailureDismissed = {},
                            selectedAppLanguage = AppLanguage.DEVICE,
                            onAppLanguageSelected = {},
                            selectedNavBarStyle = NavBarStyle.ADAPTIVE,
                            onNavBarStyleSelected = {},
                            onHomescreenClick = {},
                            onMetaScreenClick = {},
                            onStreamsClick = {},
                            onCollectionsClick = {},
                            onContinueWatchingClick = {},
                            onPosterCustomizationClick = {},
                            onHoverPreviewClick = {},
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Reduce motion").performClick()
            waitForIdle()
            assertTrue(ReducedMotion.enabled.value)
            assertEquals(0, ReducedMotion.duration(300))
        }
    }

    private fun fontScaleInsideTheme(density: Density): Float {
        var scale = 0f
        DesktopComposeUiTest(
            width = 400,
            height = 200,
            density = density,
            useStandardTestDispatcherForComposition = false,
        ).runTest {
            setContent {
                NuvioTheme {
                    scale = LocalDensity.current.fontScale
                    Text(scale.toString(), modifier = Modifier.testTag("scale"))
                }
            }
            waitForIdle()
        }
        return scale
    }

    private fun titleHeights(density: Density, title: String): Pair<Float, Float> {
        var single = 0f
        var wrapped = 0f
        DesktopComposeUiTest(
            width = 480,
            height = 240,
            density = density,
            useStandardTestDispatcherForComposition = false,
        ).runTest {
            setContent {
                NuvioTheme {
                    TitlePair(title)
                }
            }
            waitForIdle()
            single = onNodeWithTag("one-line").fetchSemanticsNode().boundsInRoot.height
            wrapped = onNodeWithTag("wrapped").fetchSemanticsNode().boundsInRoot.height
        }
        return single to wrapped
    }
}

@Composable
private fun TitlePair(title: String) {
    Column(Modifier.width(160.dp)) {
        Text(
            text = title,
            modifier = Modifier.testTag("one-line"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = title,
            modifier = Modifier.testTag("wrapped"),
            maxLines = largeTextLineLimit(),
            overflow = TextOverflow.Ellipsis,
        )
    }
}
