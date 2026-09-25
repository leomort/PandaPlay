package com.pandaplay.emu.library

import com.pandaplay.emu.GameStorage
import com.pandaplay.emu.LibraryPrefs
import com.pandaplay.emu.Platform
import java.io.File

/**
 * A biblioteca completa: jogos importados para dentro do app + jogos das pastas monitoradas.
 * É daqui que a tela inicial monta as seções (Continuar jogando, Favoritos, por console...).
 */
class GameLibrary(private val storage: GameStorage, private val prefs: LibraryPrefs) {

    class Section(val title: String, val games: List<Game>, val platform: Platform? = null)

    fun loadAll(): List<Game> {
        val hidden = prefs.hidden()
        val imported = storage.listGames().map { it to Game.Source.IMPORTED }
        val folder = prefs.scannedPaths()
            .asSequence()
            .filter { it !in hidden }
            .map { File(it) }
            .filter { it.exists() }
            .map { it to Game.Source.FOLDER }
            .toList()

        val seen = HashSet<String>()
        return (imported + folder).mapNotNull { (file, source) ->
            val platform = Platform.of(file) ?: return@mapNotNull null
            if (!seen.add(file.absolutePath)) return@mapNotNull null
            Game(
                file = file,
                platform = platform,
                source = source,
                favorite = prefs.isFavorite(file),
                lastPlayed = prefs.lastPlayed(file),
                playTimeMs = prefs.playTime(file),
                hasSave = storage.sramFile(file).exists(),
                coverFile = storage.coverFile(file),
            )
        }.sortedBy { it.title.lowercase() }
    }

    /** Seções da tela inicial, na ordem em que aparecem. */
    fun sections(all: List<Game>): List<Section> {
        val out = mutableListOf<Section>()
        val recent = all.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(12)
        if (recent.isNotEmpty()) out += Section("▶ Continuar jogando", recent)

        val favorites = all.filter { it.favorite }
        if (favorites.isNotEmpty()) out += Section("⭐ Favoritos", favorites)

        val most = all.filter { it.playTimeMs >= 5 * 60_000 }.sortedByDescending { it.playTimeMs }.take(12)
        if (most.size >= 3) out += Section("🔥 Mais jogados", most)

        for (p in Platform.entries) {
            val games = all.filter { it.platform == p }
            if (games.isNotEmpty()) out += Section("${p.label} · ${games.size}", games, p)
        }
        return out
    }

    fun search(all: List<Game>, query: String): List<Game> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { it.title.lowercase().contains(q) || it.platform.shortLabel.lowercase() == q }
    }
}
