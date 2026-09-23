package com.pandaplay.emu

import android.content.Context
import java.io.File

/**
 * Organiza os arquivos do app dentro do armazenamento privado:
 *
 *   files/roms/     -> jogos importados pelo usuário
 *   files/saves/    -> SRAM (o "save" normal do jogo, ex: salvar no PC do Centro Pokémon)
 *   files/states/   -> save states (foto instantânea da emulação)
 *   files/system/   -> BIOS opcionais (o mGBA não precisa)
 */
class GameStorage(context: Context) {

    private val base = context.filesDir

    val romsDir = File(base, "roms").apply { mkdirs() }
    val savesDir = File(base, "saves").apply { mkdirs() }
    val statesDir = File(base, "states").apply { mkdirs() }
    val systemDir = File(base, "system").apply { mkdirs() }

    fun listGames(): List<File> =
        romsDir.listFiles()
            ?.filter { it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun sramFile(rom: File) = File(savesDir, rom.nameWithoutExtension + ".srm")

    fun stateFile(rom: File, slot: Int = 0) =
        File(statesDir, "${rom.nameWithoutExtension}.state$slot")

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

    fun deleteGame(rom: File) {
        rom.delete()
        sramFile(rom).delete()
        statesDir.listFiles { f -> f.name.startsWith(rom.nameWithoutExtension + ".state") }
            ?.forEach { it.delete() }
    }

    companion object {
        /** O core mGBA roda Game Boy, Game Boy Color e Game Boy Advance. */
        val SUPPORTED_EXTENSIONS = setOf("gba", "gb", "gbc")

        fun systemLabel(rom: File) = when (rom.extension.lowercase()) {
            "gba" -> "Game Boy Advance"
            "gbc" -> "Game Boy Color"
            "gb" -> "Game Boy"
            else -> "Desconhecido"
        }
    }
}
