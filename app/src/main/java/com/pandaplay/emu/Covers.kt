package com.pandaplay.emu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * Capas dos jogos.
 *  - Jogos importados: busca a capa no projeto libretro-thumbnails pelo nome do arquivo
 *    (funciona quando o nome segue o padrão No-Intro, ex: "Pokemon - Emerald Version (USA, Europe)").
 *  - Jogos da aba "Jogos grátis": usa o primeiro screenshot do Homebrew Hub.
 */
object Covers {

    private val cache = LruCache<String, Bitmap>(80)

    /** Tenta baixar a capa se ainda não existir. Retorna true se passou a existir. */
    fun ensureCover(storage: GameStorage, prefs: LibraryPrefs, rom: File): Boolean {
        val target = storage.coverFile(rom)
        if (target.exists()) return true
        if (prefs.coverMissing(rom)) return false
        val platform = Platform.of(rom) ?: return false

        // Remove sufixos que o próprio app adiciona, como " [Tradução PT-BR]"
        val baseName = rom.nameWithoutExtension.replace(Regex("\\s*\\[[^\\]]*]\\s*$"), "")
        // Regra de nomes do libretro-thumbnails: estes caracteres viram "_"
        val thumbName = baseName.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")
        val url = "https://raw.githubusercontent.com/libretro-thumbnails/${platform.thumbnailRepo}" +
            "/master/Named_Boxarts/${Net.encodePath(thumbName)}.png"

        val bytes = Net.get(url, maxBytes = 4 * 1024 * 1024)
        if (bytes == null || bytes.isEmpty()) {
            prefs.setCoverMissing(rom)
            return false
        }
        target.writeBytes(bytes)
        return true
    }

    /** Carrega a capa reduzida (para não gastar memória com imagens grandes). */
    fun load(file: File, maxSize: Int = 256): Bitmap? {
        cache.get(file.absolutePath)?.let { return it }
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        cache.put(file.absolutePath, bmp)
        return bmp
    }

    fun invalidate(file: File) {
        cache.remove(file.absolutePath)
    }
}
