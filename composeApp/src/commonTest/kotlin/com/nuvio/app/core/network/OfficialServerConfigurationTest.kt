package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertTrue

class OfficialServerConfigurationTest {
    @Test
    fun `official sign-in client is configured`() {
        val configuration = officialConfiguration()

        assertTrue(configuration.backendUrl.startsWith("https://"), configuration.backendUrl)
        assertTrue(configuration.publishableKey.isNotBlank())
        assertTrue(configuration.capabilities.emailPasswordAuth)
        assertTrue(configuration.capabilities.tvLogin)
    }
}
