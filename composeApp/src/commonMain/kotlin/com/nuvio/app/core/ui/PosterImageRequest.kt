package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/** Catalog and grid posters opt out of the shared loader crossfade. Heroes and backdrops do not. */
fun posterImageRequest(
    context: PlatformContext,
    imageUrl: String,
    fallbackImageUrl: String? = null,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(imageUrl)
        .crossfade(false)
    if (!fallbackImageUrl.isNullOrBlank() && fallbackImageUrl != imageUrl) {
        builder.memoryCacheKeyExtras(
            mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallbackImageUrl),
        )
    }
    return builder.build()
}
