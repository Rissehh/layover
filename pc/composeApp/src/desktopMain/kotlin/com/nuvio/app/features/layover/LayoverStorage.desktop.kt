package com.nuvio.app.features.layover

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object LayoverStorage {
    private val store = DesktopStorage.store("layover")

    actual fun load(key: String): String? = store.getString(ProfileScopedKey.of(key))

    actual fun save(key: String, value: String) {
        store.putString(ProfileScopedKey.of(key), value)
    }
}
