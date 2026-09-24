package com.pandaplay.emu

import android.content.Context
import java.io.File

/**
 * Organiza os arquivos do app dentro do armazenamento privado:
 *
 *   files/roms/     -> jogos importados pelo usuário ou baixados da aba de jogos grátis
 *   files/saves/    -> SRAM (o "save" normal do jogo, ex: salvar no PC do Centro Pokémon)
 *   files/states/   -> save states (foto instantânea da emulação)
 *   files/covers/   -> capas dos jogos
 *   files/system/   -> BIOS opcionais (o mGBA não precisa)
 */
class GameStorage(context: Context) {

    private val base = context.filesDir

    val romsDir = File(base, "roms").apply { mkdirs() }
    val savesDir = File(base, "saves").apply { mkdirs() }
    val statesDir = File(base, "states").apply { mkdirs() }
    val coversDir = File(base, "covers").apply { mkdirs() }
    val systemDir = File(base, "system").apply { mkdirs() }

    /** Extensões aceitas na importação: jogos + trilhas .bin/.img de PlayStation. */
    val importableExtensions get() = SUPPORTED_EXTENSIONS + Platform.COMPANION_EXTENSIONS

    fun listGames(): List<File> =
        romsDir.listFiles()
            ?.filter { it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun sramFile(rom: File) = File(savesDir, rom.nameWithoutExtension + ".srm")

    fun stateFile(rom: File, slot: Int = 0) =
        File(statesDir, "${rom.nameWithoutExtension}.state$slot")

    fun coverFile(rom: File) = File(coversDir, rom.nameWithoutExtension + ".png")

    fun readSram(rom: File): ByteArray? =
        sramFile(rom).takeIf { it.exists() && it.length() > 0 }?.readBytes()

    /** Só grava se houver conteúdo, para nunca sobrescrever um save bom com um vazio. */
    fun writeSram(rom: File, data: ByteArray) {
        if (data.isEmpty()) return
        val target = sramFile(rom)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        tmp.renameTo(target)
    }

    /** Arquivos .bin/.img citados dentro de um .cue (as trilhas do CD). */
    fun cueTracks(cue: File): List<File> {
        if (cue.extension.lowercase() != "cue" || !cue.exists()) return emptyList()
        val regex = Regex("FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        return cue.readLines().mapNotNull { line ->
            regex.find(line)?.groupValues?.get(1)?.let { File(romsDir, File(it).name) }
        }.filter { it.exists() }
    }

    /** Trilhas que o .cue precisa e que ainda não foram importadas. */
    fun missingCueTracks(cue: File): List<String> {
        if (cue.extension.lowercase() != "cue" || !cue.exists()) return emptyList()
        val regex = Regex("FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        return cue.readLines().mapNotNull { line -> regex.find(line)?.groupValues?.get(1) }
            .map { File(it).name }
            .filter { !File(romsDir, it).exists() }
    }

    fun deleteGame(rom: File) {
        cueTracks(rom).forEach { it.delete() }
        rom.delete()
        sramFile(rom).delete()
        coverFile(rom).delete()
        statesDir.listFiles { f -> f.name.startsWith(rom.nameWithoutExtension + ".state") }
            ?.forEach { it.delete() }
    }

    /**
     * Extrai de um .zip apenas os jogos suportados. Usa só o nome do arquivo
     * (sem pastas internas) para evitar que um zip malicioso grave fora da pasta ("zip slip").
     */
    fun extractGamesFromZip(input: java.io.InputStream): List<File> {
        val created = mutableListOf<File>()
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val fileName = safeName(File(entry.name).name)
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (!entry.isDirectory && ext in importableExtensions) {
                    val out = File(romsDir, fileName)
                    out.outputStream().use { zip.copyTo(it) }
                    if (ext in SUPPORTED_EXTENSIONS) created += out
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return created
    }

    companion object {
        /** Todas as extensões de jogo de todas as plataformas. */
        val SUPPORTED_EXTENSIONS: Set<String> = Platform.entries.flatMap { it.extensions }.toSet()

        fun systemLabel(rom: File) = Platform.of(rom)?.label ?: "Desconhecido"

        /** Nome de arquivo seguro (sem caracteres proibidos no Android). */
        fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
