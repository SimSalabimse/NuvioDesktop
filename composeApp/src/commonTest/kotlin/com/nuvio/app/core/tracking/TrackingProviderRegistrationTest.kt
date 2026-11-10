package com.nuvio.app.core.tracking

import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrackingProviderRegistrationTest {
    @Test
    fun preloadAndStartupShareOneRegistration() = runBlocking {
        preloadTrackingProvidersAsync()
        ensureTrackingProvidersRegistered()
        val trakt = TrackingProviderRegistry.authProvider(TrackingProviderId.TRAKT)
        val simkl = TrackingProviderRegistry.authProvider(TrackingProviderId.SIMKL)
        ensureTrackingProvidersRegistered()
        assertTrue(trakt != null)
        assertTrue(simkl != null)
        assertSame(trakt, TrackingProviderRegistry.authProvider(TrackingProviderId.TRAKT))
        assertSame(simkl, TrackingProviderRegistry.authProvider(TrackingProviderId.SIMKL))
    }
}
