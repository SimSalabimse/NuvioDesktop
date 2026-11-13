package com.nuvio.app.features.debrid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DebridPlayOutcome(
    val stream: StreamItem?,
    val failure: DirectDebridPlayableResult? = null,
)

internal object DebridSourceFallback {
    const val MAX_TRIES = 3

    suspend fun playable(
        stream: StreamItem,
        season: Int?,
        episode: Int?,
        notify: Boolean = true,
        resolve: suspend (StreamItem, Int?, Int?) -> DirectDebridPlayableResult = { item, itemSeason, itemEpisode ->
            DirectDebridPlaybackResolver.resolveToPlayableStream(item, itemSeason, itemEpisode)
        },
        shouldResolve: (StreamItem) -> Boolean = DirectDebridPlaybackResolver::shouldResolveToPlayableStream,
        onSwitched: (String) -> Unit = { label ->
            NuvioToastController.show("Not cached, switched to $label", durationMillis = 4000L)
        },
        reportFailure: (StreamItem, DirectDebridPlayableResult) -> Unit = { item, failure ->
            notifyFailure(item, failure)
        },
        candidates: suspend () -> List<StreamItem>,
    ): DebridPlayOutcome {
        if (!shouldResolve(stream)) return DebridPlayOutcome(stream)
        val first = resolve(stream, season, episode)
        if (first is DirectDebridPlayableResult.Success) return DebridPlayOutcome(first.stream)
        if (first is DirectDebridPlayableResult.NotCached || first is DirectDebridPlayableResult.Stale) {
            nextWorking(candidates(), stream, season, episode, resolve, shouldResolve)?.let { fallback ->
                if (notify) onSwitched(fallback.fallbackLabel())
                return DebridPlayOutcome(fallback)
            }
        }
        if (notify) reportFailure(stream, first)
        return DebridPlayOutcome(null, first)
    }

    fun notifyFailure(
        stream: StreamItem,
        failure: DirectDebridPlayableResult,
        addProviderName: (StreamItem) -> String? = DirectDebridPlaybackResolver::addToCacheProviderName,
        offer: (StreamItem) -> Boolean = { item -> DebridAddOffer.offer(item, addProviderName(item)) },
        showMessage: (String) -> Unit = { message -> NuvioToastController.show(message) },
        messageFor: (DirectDebridPlayableResult) -> String? = { it.toastMessage() },
    ) {
        if (failure is DirectDebridPlayableResult.NotCached && offer(stream)) return
        messageFor(failure)?.let(showMessage)
    }

    fun handleUnresolved(
        stream: StreamItem,
        failure: DirectDebridPlayableResult,
        hasLaterCandidate: Boolean,
        notify: (StreamItem, DirectDebridPlayableResult) -> Unit = { item, result ->
            notifyFailure(item, result)
        },
    ): Boolean {
        if (!hasLaterCandidate) notify(stream, failure)
        return !hasLaterCandidate && failure == DirectDebridPlayableResult.Stale
    }

    suspend fun nextWorking(
        candidates: List<StreamItem>,
        failed: StreamItem,
        season: Int?,
        episode: Int?,
        resolve: suspend (StreamItem, Int?, Int?) -> DirectDebridPlayableResult = { item, itemSeason, itemEpisode ->
            DirectDebridPlaybackResolver.resolveToPlayableStream(item, itemSeason, itemEpisode)
        },
        shouldResolve: (StreamItem) -> Boolean = DirectDebridPlaybackResolver::shouldResolveToPlayableStream,
    ): StreamItem? = candidates
        .asSequence()
        .filter { it != failed && it.debridCacheStatus?.state != StreamDebridCacheState.NOT_CACHED }
        .filter { it.playableDirectUrl != null || shouldResolve(it) }
        .take(MAX_TRIES)
        .toList()
        .firstNotNullOfOrNull { candidate ->
            (resolve(candidate, season, episode) as? DirectDebridPlayableResult.Success)
                ?.stream?.takeIf { it.playableDirectUrl != null }
        }
}

internal object DebridAddOffer {
    private val _pending = MutableStateFlow<StreamItem?>(null)
    val pending: StateFlow<StreamItem?> = _pending.asStateFlow()

    fun offer(stream: StreamItem, providerName: String? = DirectDebridPlaybackResolver.addToCacheProviderName(stream)): Boolean {
        if (providerName == null) return false
        _pending.value = stream
        return true
    }

    fun dismiss() {
        _pending.value = null
    }
}

internal suspend fun finishDebridAdd(
    providerName: String,
    add: suspend () -> Boolean,
): String {
    val added = add()
    DebridAddOffer.dismiss()
    return debridAddResultMessage(providerName, added)
}

internal fun debridAddResultMessage(providerName: String, added: Boolean): String =
    if (added) {
        "Added to $providerName. It will be ready to play later."
    } else {
        "$providerName didn't accept it. Try again later."
    }

internal fun debridAddOfferTitle(providerName: String): String = "Not cached on $providerName"

internal fun debridAddOfferMessage(providerName: String): String =
    "No other source worked. Add it to $providerName so it downloads there and is ready to play later?"

internal fun debridAddOfferConfirm(providerName: String): String = "Add to $providerName"

internal const val DEBRID_ADD_OFFER_DISMISS = "Not now"

@Composable
internal fun DebridAddOfferHost() {
    val offered by DebridAddOffer.pending.collectAsState()
    val stream = offered ?: return
    val provider = DirectDebridPlaybackResolver.addToCacheProviderName(stream) ?: "debrid"
    val scope = rememberCoroutineScope()
    var adding by remember(stream) { mutableStateOf(false) }
    NuvioStatusModal(
        title = debridAddOfferTitle(provider),
        message = debridAddOfferMessage(provider),
        isVisible = true,
        isBusy = adding,
        confirmText = debridAddOfferConfirm(provider),
        dismissText = DEBRID_ADD_OFFER_DISMISS,
        onConfirm = {
            adding = true
            scope.launch {
                val message = finishDebridAdd(provider) {
                    DirectDebridPlaybackResolver.addToCache(stream)
                }
                NuvioToastController.show(message, durationMillis = 4000L)
            }
        },
        onDismiss = { DebridAddOffer.dismiss() },
    )
}

internal fun StreamItem.fallbackLabel(): String =
    listOf(addonName, streamLabel.lineSequence().firstOrNull().orEmpty())
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
        .take(60)
