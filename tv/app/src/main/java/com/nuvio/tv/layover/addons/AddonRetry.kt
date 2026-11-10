package com.nuvio.tv.layover.addons

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** After start-up the addons that didn't load are tried again this long after each other (the phone's 3/10/30 s). */
internal val RETRY_DELAYS_MS = listOf(3_000L, 10_000L, 30_000L)

/** An installed addon whose manifest never loaded: upstream lists it as a placeholder with nothing in it. */
internal fun Addon.isUnloaded(): Boolean = enabled && version.isBlank() && resources.isEmpty() && catalogs.isEmpty()

/**
 * Addons that failed to load stay empty until something else reloads the list (upstream only refetches a
 * missing manifest when the addon list itself changes). A TV switched on before its Wi-Fi is up, or an addon
 * server that was briefly down, would then show no sources from it for the whole session. This tries the
 * unloaded ones again a few times after start-up and whenever the network comes back.
 */
@Singleton
class AddonRetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addons: AddonRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            for (wait in RETRY_DELAYS_MS) {
                delay(wait)
                retryUnloaded()
            }
        }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scope.launch {
                            delay(2_000)   // let DNS settle on the new network
                            retryUnloaded()
                        }
                    }
                }
            )
        }.onFailure { Log.w(TAG, "no network callback", it) }
    }

    private suspend fun retryUnloaded() {
        val installed = withTimeoutOrNull(5_000) { addons.getInstalledAddons().first { it.isNotEmpty() } } ?: return
        val unloaded = installed.filter { it.isUnloaded() }
        if (unloaded.isEmpty()) return
        Log.i(TAG, "retrying ${unloaded.size} addon(s) that did not load")
        unloaded.forEach { runCatching { addons.fetchAddon(it.baseUrl) } }
    }

    private companion object {
        const val TAG = "LayoverAddonRetry"
    }
}
