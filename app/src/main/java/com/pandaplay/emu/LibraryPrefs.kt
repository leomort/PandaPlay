package com.pandaplay.emu

import android.content.Context
import java.io.File

/**
 * Dados da biblioteca que não estão nos arquivos: favoritos, histórico, tempo jogado,
 * pastas monitoradas, jogos ocultos e capas inexistentes (para não tentar baixar toda hora).
 */
class LibraryPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ por jogo

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

    fun playTime(rom: File): Long = prefs.getLong("time_" + rom.name, 0L)

    fun addPlayTime(rom: File, ms: Long) {
        if (ms <= 0) return
        prefs.edit().putLong("time_" + rom.name, playTime(rom) + ms).apply()
    }

    fun coverMissing(rom: File) = prefs.getBoolean("nocover_" + rom.name, false)

    fun setCoverMissing(rom: File) {
        prefs.edit().putBoolean("nocover_" + rom.name, true).apply()
    }

    // ------------------------------------------------------------ pastas monitoradas

    fun folders(): Set<String> = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()

    fun addFolder(path: String) {
        prefs.edit().putStringSet("folders", folders() + path).apply()
    }

    fun removeFolder(path: String) {
        prefs.edit().putStringSet("folders", folders() - path).apply()
    }

    /** Jogos encontrados na última varredura (caminhos completos). */
    fun scannedPaths(): Set<String> = prefs.getStringSet("scanned", emptySet())?.toSet() ?: emptySet()

    fun setScannedPaths(paths: Set<String>) {
        prefs.edit().putStringSet("scanned", paths).putLong("scanned_at", System.currentTimeMillis()).apply()
    }

    fun lastScan(): Long = prefs.getLong("scanned_at", 0L)

    // ------------------------------------------------------------ ocultos (jogos de pasta)

    fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()

    fun hide(path: String) {
        prefs.edit().putStringSet("hidden", hidden() + path).apply()
    }

    fun unhideAll() {
        prefs.edit().remove("hidden").apply()
    }
}
