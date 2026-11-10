package com.nuvio.tv.layover

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.layover.log.AttemptLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layover's single start-up hook (called from NuvioApplication.onCreate): opens Layover's
 * storage, loads its settings and attempt log, and runs the one-time autoplay migration for
 * every profile that becomes active.
 */
@Singleton
class LayoverStartup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val profileManager: ProfileManager,
    private val appOnboardingDataStore: AppOnboardingDataStore,
    private val addonRetry: com.nuvio.tv.layover.addons.AddonRetry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        LayoverStorage.initialize(context)
        LayoverNetwork.initialize(context)
        LayoverSettingsRepository.reload()
        AttemptLog.reload()
        addonRetry.start()
        // Without a Nuvio account server (LayoverTV builds have none) the first-launch sign-in screen can only
        // show an error: skip straight to the app, as "Continue without account" would.
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            scope.launch { runCatching { appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true) } }
        }
        scope.launch {
            profileManager.activeProfileReady.first { it }
            profileManager.activeProfileId.collect { profileId ->
                runCatching { migrationFor(profileId).run() }
                    .onFailure { Log.w(TAG, "Autoplay migration failed for profile $profileId", it) }
            }
        }
    }

    private fun migrationFor(profileId: Int): LayoverAutoplayMigration {
        val key = LayoverAutoplayMigration.flagKey(profileId)
        return LayoverAutoplayMigration(
            loadFlag = { LayoverStorage.load(key) },
            saveFlag = { LayoverStorage.save(key, it) },
            currentMode = { playerSettingsDataStore.playerSettings.first().streamAutoPlayMode },
            setMode = { playerSettingsDataStore.setStreamAutoPlayMode(it) },
        )
    }

    private companion object {
        const val TAG = "LayoverStartup"
    }
}
