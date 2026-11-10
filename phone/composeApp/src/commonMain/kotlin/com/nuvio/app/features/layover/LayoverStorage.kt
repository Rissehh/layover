package com.nuvio.app.features.layover

internal expect object LayoverStorage {
    fun load(key: String): String?
    fun save(key: String, value: String)
}
