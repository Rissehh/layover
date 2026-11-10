package com.nuvio.app.features.layover

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.layover.downloads.LayoverFreeSpace

internal actual object LayoverStorage {
    private const val preferencesName = "layover_store"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        LayoverNetwork.initialize(context)
        LayoverFreeSpace.initialize(context)
    }

    actual fun load(key: String): String? =
        preferences?.getString(ProfileScopedKey.of(key), null)

    actual fun save(key: String, value: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(key), value)
            ?.apply()
    }
}
