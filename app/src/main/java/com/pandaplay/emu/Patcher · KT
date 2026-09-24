package com.pandaplay.emu

import java.util.zip.CRC32

/**
 * Aplica patches de tradução/romhack nos formatos mais usados:
 *   .ips -> formato antigo, sem verificação de versão
 *   .ups -> verifica o CRC32 do jogo original e do resultado
 *   .bps -> formato moderno, também verifica CRC32
 *
 * Tudo acontece em memória: o jogo original nunca é alterado.
 */
object Patcher {

    enum class Format { IPS, UPS, BPS }

    class PatchException(message: String) : Exception(message)

    /**
     * @param sourceMatches false quando o patch declara um CRC de jogo diferente do seu
     *        (versão errada da ROM). IPS não tem essa informação, então sempre vem null.
     */
    class Result(
        val data: ByteArray,
        val format: Format,
        val sourceMatches: Boolean?,
        val expectedSourceCrc: Long?,
        val actualSourceCrc: Long,
    )

    fun isPatchName(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("ips", "ups", "bps")

    fun crc32(data: ByteArray, length: Int = data.size): Long =
        CRC32().apply { update(data, 0, length) }.value

    fun crcHex(value: Long) = "%08X".format(value)

    /** Descobre o formato pelo cabeçalho do arquivo (não depende da extensão). */
    fun detect(patch: ByteArray): Format = when {
        patch.startsWith("PATCH") -> Format.IPS
        patch.startsWith("UPS1") -> Format.UPS
        patch.startsWith("BPS1") -> Format.BPS
        else -> throw PatchException("Arquivo não é um patch IPS, UPS ou BPS válido")
    }

    fun apply(source: ByteArray, patch: ByteArray): Result {
        val format = detect(patch)
        val sourceCrc = crc32(source)
        return when (format) {
            Format.IPS -> Result(applyIps(source, patch), format, null, null, sourceCrc)
            Format.UPS -> applyUps(source, patch, sourceCrc)
            Format.BPS -> applyBps(source, patch, sourceCrc)
        }
    }

    // ------------------------------------------------------------------ IPS

    private fun applyIps(source: ByteArray, patch: ByteArray): ByteArray {
        var out = source.copyOf()
        var size = out.size
        var p = 5

        fun ensure(len: Int) {
            if (len > out.size) out = out.copyOf(maxOf(len, out.size * 2))
            if (len > size) size = len
        }

        while (true) {
            if (p + 3 > patch.size) throw PatchException("Patch IPS incompleto")
            if (patch[p] == 'E'.code.toByte() && patch[p + 1] == 'O'.code.toByte() &&
                patch[p + 2] == 'F'.code.toByte()
            ) {
                p += 3
                break
            }
            val offset = u8(patch, p) shl 16 or (u8(patch, p + 1) shl 8) or u8(patch, p + 2)
            p += 3
            if (p + 2 > patch.size) throw PatchException("Patch IPS incompleto")
            val len = u8(patch, p) shl 8 or u8(patch, p + 1)
            p += 2
            if (len > 0) {
                if (p + len > patch.size) throw PatchException("Patch IPS incompleto")
                ensure(offset + len)
                System.arraycopy(patch, p, out, offset, len)
                p += len
            } else { // RLE: repete um mesmo byte várias vezes
                if (p + 3 > patch.size) throw PatchException("Patch IPS incompleto")
                val rle = u8(patch, p) shl 8 or u8(patch, p + 1)
                val value = patch[p + 2]
                p += 3
                ensure(offset + rle)
                out.fill(value, offset, offset + rle)
            }
        }
        // Extensão opcional: tamanho final do arquivo (truncar)
        if (p + 3 <= patch.size) {
            size = u8(patch, p) shl 16 or (u8(patch, p + 1) shl 8) or u8(patch, p + 2)
        }
        return out.copyOf(size)
    }

    // ------------------------------------------------------------------ UPS

    private fun applyUps(source: ByteArray, patch: ByteArray, sourceCrc: Long): Result {
        if (patch.size < 16) throw PatchException("Patch UPS incompleto")
        checkPatchCrc(patch)
        val footer = patch.size - 12
        val expectedSource = u32(patch, footer)
        val expectedTarget = u32(patch, footer + 4)

        val r = Reader(patch, 4)
        val sourceSize = r.varint()
        val targetSize = r.varint()
        if (targetSize > Int.MAX_VALUE || sourceSize > Int.MAX_VALUE) {
            throw PatchException("Tamanho inválido no patch UPS")
        }

        val out = ByteArray(targetSize.toInt())
        System.arraycopy(source, 0, out, 0, minOf(source.size, out.size))

        var pos = 0L
        while (r.pos < footer) {
            pos += r.varint()
            while (true) {
                if (r.pos >= footer) throw PatchException("Patch UPS corrompido")
                val x = patch[r.pos++]
                if (x.toInt() == 0) { pos++; break }
                if (pos < out.size) {
                    val src = if (pos < source.size) source[pos.toInt()] else 0
                    out[pos.toInt()] = (src.toInt() xor x.toInt()).toByte()
                }
                pos++
            }
        }

        val matches = sourceCrc == expectedSource && source.size.toLong() == sourceSize
        if (matches && crc32(out) != expectedTarget) {
            throw PatchException("O resultado não confere com o esperado pelo patch")
        }
        return Result(out, Format.UPS, matches, expectedSource, sourceCrc)
    }

    // ------------------------------------------------------------------ BPS

    private fun applyBps(source: ByteArray, patch: ByteArray, sourceCrc: Long): Result {
        if (patch.size < 16) throw PatchException("Patch BPS incompleto")
        checkPatchCrc(patch)
        val footer = patch.size - 12
        val expectedSource = u32(patch, footer)
        val expectedTarget = u32(patch, footer + 4)

        val r = Reader(patch, 4)
        val sourceSize = r.varint()
        val targetSize = r.varint()
        val metadataSize = r.varint()
        if (targetSize > Int.MAX_VALUE) throw PatchException("Tamanho inválido no patch BPS")
        r.pos += metadataSize.toInt()

        val matches = sourceCrc == expectedSource && source.size.toLong() == sourceSize
        val out = ByteArray(targetSize.toInt())
        var outPos = 0
        var sourceRel = 0
        var targetRel = 0

        fun srcByte(i: Int): Byte =
            if (i in source.indices) source[i] else throw PatchException(versionHint(matches))

        while (r.pos < footer) {
            val data = r.varint()
            val command = (data and 3L).toInt()
            val length = ((data shr 2) + 1).toInt()
            if (outPos + length > out.size) throw PatchException("Patch BPS corrompido")
            when (command) {
                0 -> repeat(length) { out[outPos] = srcByte(outPos); outPos++ }             // SourceRead
                1 -> {                                                                     // TargetRead
                    if (r.pos + length > footer) throw PatchException("Patch BPS corrompido")
                    System.arraycopy(patch, r.pos, out, outPos, length)
                    r.pos += length; outPos += length
                }
                2 -> {                                                                     // SourceCopy
                    val d = r.varint()
                    sourceRel += ((if (d and 1L == 1L) -1 else 1) * (d shr 1)).toInt()
                    repeat(length) { out[outPos++] = srcByte(sourceRel++) }
                }
                else -> {                                                                  // TargetCopy
                    val d = r.varint()
                    targetRel += ((if (d and 1L == 1L) -1 else 1) * (d shr 1)).toInt()
                    if (targetRel < 0) throw PatchException("Patch BPS corrompido")
                    repeat(length) { out[outPos++] = out[targetRel++] }
                }
            }
        }

        if (matches && crc32(out) != expectedTarget) {
            throw PatchException("O resultado não confere com o esperado pelo patch")
        }
        return Result(out, Format.BPS, matches, expectedSource, sourceCrc)
    }

    // ------------------------------------------------------------------ utilidades

    private fun versionHint(matches: Boolean) =
        if (matches) "Patch BPS corrompido"
        else "Este patch foi feito para outra versão do jogo e não pode ser aplicado"

    private fun checkPatchCrc(patch: ByteArray) {
        val expected = u32(patch, patch.size - 4)
        if (crc32(patch, patch.size - 4) != expected) {
            throw PatchException("O arquivo do patch está corrompido (download incompleto?)")
        }
    }

    private fun ByteArray.startsWith(magic: String) =
        size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    /** Números de tamanho variável usados pelos formatos UPS e BPS. */
    private class Reader(val data: ByteArray, var pos: Int) {
        fun varint(): Long {
            var value = 0L
            var shift = 1L
            while (true) {
                if (pos >= data.size) throw PatchException("Patch corrompido")
                val x = data[pos++].toInt() and 0xFF
                value += (x and 0x7F) * shift
                if (x and 0x80 != 0) break
                shift = shift shl 7
                value += shift
            }
            return value
        }
    }
}
