package com.nuvio.app.core.tracking

import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklMutationRepository
import com.nuvio.app.features.simkl.SimklLibraryRepository
import com.nuvio.app.features.simkl.SimklProgressRepository
import com.nuvio.app.features.simkl.SimklTrackingLibraryProvider
import com.nuvio.app.features.simkl.SimklTrackingProgressProvider
import com.nuvio.app.features.simkl.SimklWatchedSyncAdapter
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.mdblist.MdbListTracker
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktScrobbleRepository
import com.nuvio.app.features.trakt.TraktTrackingLibraryProvider
import com.nuvio.app.features.trakt.TraktTrackingProgressProvider
import com.nuvio.app.features.watching.sync.TraktWatchedSyncAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val registrationLock = Any()
private var providersRegistered = false
private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun ensureTrackingProvidersRegistered() {
    synchronized(registrationLock) {
        if (providersRegistered) return
        MdbListTracker.register()
        TraktAuthRepository.descriptor
        TraktScrobbleRepository.ensureRegistered()
        SimklAuthRepository.descriptor
        SimklSyncRepository.state
        SimklLibraryRepository.uiState
        SimklProgressRepository.uiState
        SimklMutationRepository.ensureRegistered()
        TrackingProviderRegistry.registerLibraryProvider(TraktTrackingLibraryProvider)
        TrackingProviderRegistry.registerLibraryProvider(SimklTrackingLibraryProvider)
        TrackingProviderRegistry.registerWatchedProvider(TraktWatchedSyncAdapter)
        TrackingProviderRegistry.registerWatchedProvider(SimklWatchedSyncAdapter)
        TrackingProviderRegistry.registerProgressProvider(TraktTrackingProgressProvider)
        TrackingProviderRegistry.registerProgressProvider(SimklTrackingProgressProvider)
        providersRegistered = true
    }
}

internal fun preloadTrackingProvidersAsync() {
    preloadScope.launch {
        ensureTrackingProvidersRegistered()
    }
}
