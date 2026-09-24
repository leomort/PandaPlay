package com.pandaplay.emu

import android.content.Context
import java.io.File

/** Favoritos, "jogado por último" e capas que não existem (para não tentar baixar toda hora). */
class LibraryPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun isFavorite(rom: File) = prefs.getBoolean("fav_" + rom.name, false)

    fun toggleFavorite(rom: File): Boolean {
        val now = !isFavorite(rom)
        prefs.edit().putBoolean("fav_" + rom.name, now).apply()
        return now
    }

    fun lastPlayed(rom: File): Long = prefs.getLong("last_" + rom.name, 0L)

    fun markPlayed(rom: File) {
        prefs.edit().putLong("last_" + rom.name, System.currentTimeMillis()).apply()
    }

    fun coverMissing(rom: File) = prefs.getBoolean("nocover_" + rom.name, false)

    fun setCoverMissing(rom: File) {
        prefs.edit().putBoolean("nocover_" + rom.name, true).apply()
    }
}
