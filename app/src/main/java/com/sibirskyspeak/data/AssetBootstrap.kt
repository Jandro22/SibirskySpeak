package com.sibirskyspeak.data

import android.content.Context

class AssetBootstrap(private val context: Context) {
    suspend fun readTextAsset(name: String): String? =
        runCatching {
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
}
