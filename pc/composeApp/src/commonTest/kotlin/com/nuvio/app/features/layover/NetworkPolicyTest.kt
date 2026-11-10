package com.nuvio.app.features.layover

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkPolicyTest {
    @Test
    fun `capabilities map to three network qualities`() {
        assertEquals(NetworkQuality.None, networkQualityOf(hasActiveNetwork = false, hasInternet = false, validated = false))
        assertEquals(NetworkQuality.None, networkQualityOf(hasActiveNetwork = true, hasInternet = false, validated = false))
        assertEquals(NetworkQuality.None, networkQualityOf(hasActiveNetwork = true, hasInternet = false, validated = true))
        assertEquals(NetworkQuality.Unvalidated, networkQualityOf(hasActiveNetwork = true, hasInternet = true, validated = false))
        assertEquals(NetworkQuality.Validated, networkQualityOf(hasActiveNetwork = true, hasInternet = true, validated = true))
    }

    @Test
    fun `validated judges and records, unvalidated judges only, none does neither`() {
        assertEquals(FallbackNetworkPolicy(judge = true, record = true), NetworkQuality.Validated.fallbackPolicy())
        assertEquals(FallbackNetworkPolicy(judge = true, record = false), NetworkQuality.Unvalidated.fallbackPolicy())
        assertEquals(FallbackNetworkPolicy(judge = false, record = false), NetworkQuality.None.fallbackPolicy())
    }
}
