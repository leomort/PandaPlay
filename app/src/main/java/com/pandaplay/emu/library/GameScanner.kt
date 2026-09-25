package com.pandaplay.emu.library

import com.pandaplay.emu.Platform
import java.io.File

/**
 * Procura jogos dentro das pastas escolhidas pelo usuário (e nas subpastas).
 * Os jogos são lidos no próprio lugar: nada é copiado.
 */
object GameScanner {

    private const val MAX_DEPTH = 8
    private val SKIP_DIRS = setOf("android", "lost.dir", "dcim", "whatsapp", "telegram")

    fun scan(folders: Collection<String>): Set<String> {
        val found = LinkedHashSet<String>()
        for (path in folders) {
            val root = File(path)
            if (!root.isDirectory || !root.canRead()) continue
            root.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .onEnter { dir -> dir == root || (!dir.name.startsWith(".") && dir.name.lowercase() !in SKIP_DIRS) }
                .filter { it.isFile && !it.name.startsWith(".") && Platform.of(it) != null }
                .forEach { found += it.absolutePath }
        }
        return removeRedundant(found)
    }

    /**
     * No PlayStation, um jogo pode ter .m3u (lista de discos) e vários .cue:
     * quando existe o .m3u, os .cue citados nele não aparecem separados.
     */
    private fun removeRedundant(paths: Set<String>): Set<String> {
        val m3uRefs = paths.filter { it.endsWith(".m3u", ignoreCase = true) }.flatMap { m3u ->
            val dir = File(m3u).parentFile
            runCatching { File(m3u).readLines() }.getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { File(dir, it).absolutePath }
        }.toSet()
        return paths.filterNot { it in m3uRefs }.toCollection(LinkedHashSet())
    }

    /** Resumo por console, para mostrar depois da varredura. */
    fun countByPlatform(paths: Collection<String>): Map<Platform, Int> =
        paths.mapNotNull { Platform.of(File(it)) }.groupingBy { it }.eachCount()
            .toList().sortedBy { it.first.ordinal }.toMap()
}
