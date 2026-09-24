package com.pandaplay.emu

import org.json.JSONObject

/**
 * Cliente da API pública do Homebrew Hub (https://hh.gbdev.io):
 * o maior arquivo de jogos independentes de Game Boy, Game Boy Color, GBA e NES,
 * distribuídos com permissão dos autores.
 *
 * Busca:    https://hh3.gbdev.io/api/search
 * Arquivos: repositórios oficiais do banco de dados no GitHub
 */
object HomebrewHub {

    private const val API = "https://hh3.gbdev.io/api/search"
    const val PAGE_SIZE = 20

    class Entry(
        val slug: String,
        val title: String,
        val developer: String,
        val platform: String,
        val tags: List<String>,
        val license: String,
        val romFile: String,
        val screenshot: String?,
        private val rawBase: String,
    ) {
        val romUrl get() = "$rawBase/entries/$slug/${Net.encodePath(romFile)}"
        val screenshotUrl get() = screenshot?.let { "$rawBase/entries/$slug/${Net.encodePath(it)}" }
    }

    class Page(val entries: List<Entry>, val page: Int, val pageTotal: Int, val total: Int)

    /** @return null se não houver internet ou a API estiver fora do ar. */
    fun search(platform: Platform, query: String, page: Int): Page? {
        val code = platform.homebrewCode ?: return null
        val url = buildString {
            append(API)
            append("?typetag=game&platform=").append(code)
            append("&results=").append(PAGE_SIZE)
            append("&page=").append(page)
            if (query.isNotBlank()) append("&q=").append(java.net.URLEncoder.encode(query.trim(), "UTF-8"))
        }
        val bytes = Net.get(url, maxBytes = 8 * 1024 * 1024) ?: return null
        return try {
            parse(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(json: JSONObject): Page {
        val list = mutableListOf<Entry>()
        val arr = json.optJSONArray("entries")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                parseEntry(arr.getJSONObject(i))?.let { list += it }
            }
        }
        return Page(
            entries = list,
            page = json.optInt("page_current", 1),
            pageTotal = json.optInt("page_total", 1),
            total = json.optInt("results", list.size),
        )
    }

    private fun parseEntry(o: JSONObject): Entry? {
        val basepath = o.optString("basepath")
        val rawBase = when {
            basepath.contains("nes") -> "https://raw.githubusercontent.com/nesdev-org/homebrew-db/master"
            basepath.contains("gba") -> "https://raw.githubusercontent.com/gbadev-org/games/master"
            basepath.contains("gb") -> "https://raw.githubusercontent.com/gbdev/database/master"
            else -> return null // outras plataformas: ainda não suportadas pelo app
        }

        // Escolhe o arquivo principal do jogo (o marcado como "default", senão o primeiro jogável)
        val files = o.optJSONArray("files") ?: return null
        var rom: String? = null
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("filename")
            val ext = name.substringAfterLast('.', "").lowercase()
            val usable = ext in GameStorage.SUPPORTED_EXTENSIONS || ext == "zip"
            if (!usable) continue
            if (f.optBoolean("default", false)) { rom = name; break }
            if (rom == null && f.optBoolean("playable", true)) rom = name
        }
        if (rom == null) return null

        val tags = mutableListOf<String>()
        o.optJSONArray("tags")?.let { t -> for (i in 0 until t.length()) tags += t.optString(i) }

        val shots = o.optJSONArray("screenshots")
        val shot = if (shots != null && shots.length() > 0) shots.optString(0) else null

        return Entry(
            slug = o.optString("slug"),
            title = o.optString("title", o.optString("slug")),
            developer = o.optString("developer", "Autor desconhecido"),
            platform = o.optString("platform"),
            tags = tags,
            license = o.optString("license", ""),
            romFile = rom,
            screenshot = shot,
            rawBase = rawBase,
        )
    }
}
