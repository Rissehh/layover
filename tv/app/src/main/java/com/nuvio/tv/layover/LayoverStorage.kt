package com.nuvio.tv.layover

import android.content.Context
import android.content.SharedPreferences

/**
 * Small key/value store for Layover's own state (settings, attempt log, one-time flags), in a
 * private SharedPreferences file. Synchronous on purpose: the autoplay selector and the player
 * ranking read it inline. Until [initialize] runs (and in JVM tests) nothing is stored.
 */
object LayoverStorage {
    private const val FILE = "layover"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun load(key: String): String? = prefs?.getString(key, null)

    fun save(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }
}
