package com.pandaplay.emu.library

import com.pandaplay.emu.Platform
import java.io.File

/**
 * Um jogo da biblioteca. Junta o arquivo com tudo que o app sabe sobre ele:
 * console, nome limpo, favorito, histórico e tempo jogado.
 */
data class Game(
    val file: File,
    val platform: Platform,
    val source: Source,
    val favorite: Boolean,
    val lastPlayed: Long,
    val playTimeMs: Long,
    val hasSave: Boolean,
    val coverFile: File,
) {
    enum class Source { IMPORTED, FOLDER }

    val id: String get() = file.absolutePath

    /** Nome sem a região e códigos: "Pokemon - Emerald Version (USA, Europe)" -> "Pokemon - Emerald Version". */
    val title: String get() = cleanTitle(file.nameWithoutExtension)

    companion object {
        fun cleanTitle(raw: String): String {
            val clean = raw.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s{2,}"), " ").trim()
            return clean.ifEmpty { raw }
        }

        /** "8 h 42 min", "35 min" ou "menos de 1 min". */
        fun formatPlayTime(ms: Long): String {
            val totalMin = ms / 60_000
            val h = totalMin / 60
            val m = totalMin % 60
            return when {
                h > 0 -> "$h h ${m.toString().padStart(2, '0')} min"
                m > 0 -> "$m min"
                else -> "menos de 1 min"
            }
        }
    }
}
