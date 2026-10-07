package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import com.nuvio.app.features.settings.ThemeSettingsRepository

/** Catalog and grid posters opt out of the shared loader crossfade unless fade-in is on. */
fun posterImageRequest(
    context: PlatformContext,
    imageUrl: String,
    fallbackImageUrl: String? = null,
    crossfadeEnabled: Boolean = false,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(imageUrl)
        .crossfade(crossfadeEnabled)
    if (!fallbackImageUrl.isNullOrBlank() && fallbackImageUrl != imageUrl) {
        builder.memoryCacheKeyExtras(
            mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallbackImageUrl),
        )
    }
    return builder.build()
}

@Composable
fun rememberPosterCrossfadeEnabled(): Boolean {
    val enabled by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.posterFadeEnabled
    }.collectAsStateWithLifecycle()
    return enabled
}
