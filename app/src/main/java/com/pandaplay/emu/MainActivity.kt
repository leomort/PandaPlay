package com.pandaplay.emu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Biblioteca de jogos. Funciona com toque, mouse, teclado,
 * controle Bluetooth e controle remoto da TV (a ListView é navegável por D-pad).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var storage: GameStorage
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var games: List<File> = emptyList()

    /** Jogo selecionado ao importar/exportar um save. */
    private var pendingSaveTarget: File? = null

    // Usa o seletor de arquivos do sistema (SAF): não precisa pedir permissão de armazenamento.
    private val pickRoms =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importRoms(uris)
        }

    private val pickSave =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val rom = pendingSaveTarget
            if (uri != null && rom != null) importSave(uri, rom)
        }

    private val pickPatch =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val rom = pendingSaveTarget
            if (uri != null && rom != null) onPatchPicked(uri, rom)
        }

    private val exportSave =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val rom = pendingSaveTarget
            if (uri != null && rom != null) exportSave(uri, rom)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        storage = GameStorage(this)

        listView = findViewById(R.id.gameList)
        emptyView = findViewById(R.id.emptyView)

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            pickRoms.launch(arrayOf("*/*"))
        }

        listView.setOnItemClickListener { _, _, position, _ -> play(games[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showGameOptions(games[position]); true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        games = storage.listGames()
        emptyView.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (games.isEmpty()) View.GONE else View.VISIBLE
        listView.adapter = GameAdapter(games)
        if (games.isNotEmpty()) listView.requestFocus() // facilita navegar com controle/TV
    }

    private fun play(rom: File) {
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_ROM_PATH, rom.absolutePath)
        )
    }

    private fun showGameOptions(rom: File) {
        val options = arrayOf(
            "Jogar",
            "Aplicar tradução (patch)",
            "Importar save (.sav/.srm)",
            "Exportar save",
            "Informações do jogo",
            "Remover da biblioteca",
        )
        AlertDialog.Builder(this)
            .setTitle(rom.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> play(rom)
                    1 -> { pendingSaveTarget = rom; pickPatch.launch(arrayOf("*/*")) }
                    2 -> { pendingSaveTarget = rom; pickSave.launch(arrayOf("*/*")) }
                    3 -> { pendingSaveTarget = rom; exportSave.launch(rom.nameWithoutExtension + ".sav") }
                    4 -> showInfo(rom)
                    5 -> confirmDelete(rom)
                }
            }
            .show()
    }

    /** Mostra o CRC32: é o que as páginas de tradução pedem para conferir a versão certa. */
    private fun showInfo(rom: File) = lifecycleScope.launch {
        val crc = withContext(Dispatchers.IO) { Patcher.crcHex(Patcher.crc32(rom.readBytes())) }
        AlertDialog.Builder(this@MainActivity)
            .setTitle(rom.nameWithoutExtension)
            .setMessage(
                "Sistema: ${GameStorage.systemLabel(rom)}\n" +
                    "Tamanho: ${rom.length() / 1024} KB\n" +
                    "CRC32: $crc\n\n" +
                    "Confira esse CRC32 com o informado na página da tradução antes de aplicar o patch."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------- traduções (patches)

    private fun onPatchPicked(uri: Uri, rom: File) = lifecycleScope.launch {
        val name = displayName(uri) ?: "arquivo"
        val bytes = try {
            withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        } catch (e: Exception) {
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            showError("Não foi possível ler o arquivo", "Tente copiar o arquivo para a pasta Downloads e escolher de novo.")
            return@launch
        }

        // Identifica pelo conteúdo, não só pelo nome (alguns apps de arquivos escondem a extensão).
        when (sniff(bytes)) {
            Kind.PATCH -> applyPatch(rom, name, bytes)
            Kind.ZIP -> {
                val content = try {
                    withContext(Dispatchers.IO) { scanZip(bytes) }
                } catch (e: Exception) {
                    showError(
                        "Não foi possível abrir o .zip",
                        "O arquivo parece estar corrompido ou usa uma compressão não suportada.\n" +
                            "Extraia com o ZArchiver e escolha o arquivo .ips, .ups ou .bps diretamente."
                    )
                    return@launch
                }
                when {
                    content.patches.size == 1 ->
                        applyPatch(rom, content.patches[0].first, content.patches[0].second)
                    content.patches.size > 1 -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("Escolha o patch")
                        .setItems(content.patches.map { it.first }.toTypedArray()) { _, i ->
                            applyPatch(rom, content.patches[i].first, content.patches[i].second)
                        }
                        .show()
                    content.archives.isNotEmpty() -> showError(
                        "O patch está dentro de outro arquivo compactado",
                        "Dentro deste .zip há: ${content.archives.joinToString()}.\n\n" +
                            "Extraia com o ZArchiver até chegar no arquivo .ips, .ups ou .bps e escolha ele."
                    )
                    content.games.isNotEmpty() -> showError(
                        "Esse .zip é um jogo, não uma tradução",
                        "Ele contém: ${content.games.joinToString()}.\n\n" +
                            "Para aplicar a tradução, escolha o arquivo do patch (.ips, .ups ou .bps)."
                    )
                    else -> showError(
                        "Nenhum patch encontrado",
                        "Arquivos dentro do .zip: ${content.others.take(10).joinToString().ifEmpty { "(vazio)" }}"
                    )
                }
            }
            Kind.SEVEN_ZIP, Kind.RAR -> showError(
                "Formato ainda não suportado",
                "Este arquivo é .7z ou .rar. Abra no ZArchiver, toque em Extrair e depois escolha aqui o arquivo .ips, .ups ou .bps que saiu de dentro."
            )
            Kind.UNKNOWN -> showError(
                "Arquivo não reconhecido",
                "\"$name\" não é um patch .ips, .ups, .bps nem um .zip com um deles."
            )
        }
    }

    private enum class Kind { PATCH, ZIP, SEVEN_ZIP, RAR, UNKNOWN }

    private fun sniff(b: ByteArray): Kind {
        fun starts(vararg magic: Int) = b.size >= magic.size &&
            magic.indices.all { (b[it].toInt() and 0xFF) == magic[it] }
        return when {
            starts(0x50, 0x41, 0x54, 0x43, 0x48) -> Kind.PATCH   // "PATCH" (IPS)
            starts(0x55, 0x50, 0x53, 0x31) -> Kind.PATCH         // "UPS1"
            starts(0x42, 0x50, 0x53, 0x31) -> Kind.PATCH         // "BPS1"
            starts(0x50, 0x4B, 0x03, 0x04) -> Kind.ZIP           // "PK.."
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVEN_ZIP
            starts(0x52, 0x61, 0x72, 0x21) -> Kind.RAR           // "Rar!"
            else -> Kind.UNKNOWN
        }
    }

    private class ZipContent(
        val patches: List<Pair<String, ByteArray>>,
        val archives: List<String>,
        val games: List<String>,
        val others: List<String>,
    )

    /** Lê o .zip em memória e separa o que tem dentro. Detecta o patch pelo conteúdo. */
    private fun scanZip(zipBytes: ByteArray): ZipContent {
        val patches = mutableListOf<Pair<String, ByteArray>>()
        val archives = mutableListOf<String>()
        val games = mutableListOf<String>()
        val others = mutableListOf<String>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = File(entry.name).name
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    when {
                        ext in GameStorage.SUPPORTED_EXTENSIONS -> games += fileName
                        ext in setOf("zip", "7z", "rar") -> archives += fileName
                        Patcher.isPatchName(fileName) -> patches += fileName to zip.readBytes()
                        ext in setOf("txt", "nfo", "doc", "docx", "pdf", "png", "jpg", "url", "html") -> others += fileName
                        else -> {
                            // Sem extensão conhecida: confere se é um patch pelo cabeçalho
                            val data = zip.readBytes()
                            if (sniff(data) == Kind.PATCH) patches += fileName to data else others += fileName
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ZipContent(patches, archives, games, others)
    }

    private fun applyPatch(rom: File, patchName: String, patch: ByteArray, force: Boolean = false) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.Default) { Patcher.apply(rom.readBytes(), patch) }
            } catch (e: Patcher.PatchException) {
                showError("Não foi possível aplicar", e.message ?: "Erro desconhecido"); return@launch
            } catch (e: OutOfMemoryError) {
                showError("Não foi possível aplicar", "Memória insuficiente no aparelho."); return@launch
            }

            if (result.sourceMatches == false && !force) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Versão do jogo diferente")
                    .setMessage(
                        "Este patch foi feito para outra versão do jogo.\n\n" +
                            "CRC32 esperado: ${Patcher.crcHex(result.expectedSourceCrc ?: 0)}\n" +
                            "CRC32 do seu jogo: ${Patcher.crcHex(result.actualSourceCrc)}\n\n" +
                            "Aplicar mesmo assim pode gerar textos quebrados ou travamentos."
                    )
                    .setPositiveButton("Aplicar mesmo assim") { _, _ -> applyPatch(rom, patchName, patch, force = true) }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return@launch
            }

            val label = patchName.substringBeforeLast('.')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(40)
            val out = File(storage.romsDir, "${rom.nameWithoutExtension} [$label].${rom.extension}")
            withContext(Dispatchers.IO) { out.writeBytes(result.data) }

            val note = if (result.format == Patcher.Format.IPS)
                "\n\nPatches .ips não verificam a versão do jogo. Se aparecer algo estranho, confira o CRC32 em \"Informações do jogo\"."
            else ""
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Tradução aplicada ✅")
                .setMessage("Criado: ${out.nameWithoutExtension}\nO jogo original continua intacto.$note")
                .setPositiveButton("Jogar agora") { _, _ -> play(out) }
                .setNegativeButton("OK", null)
                .show()
            refresh()
        }
    }

    private fun showError(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toastLong(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun confirmDelete(rom: File) {
        AlertDialog.Builder(this)
            .setTitle("Remover ${rom.nameWithoutExtension}?")
            .setMessage("O jogo, o save e os save states serão apagados deste aparelho. Exporte o save antes se quiser guardar o progresso.")
            .setPositiveButton("Remover") { _, _ -> storage.deleteGame(rom); refresh() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun importRoms(uris: List<Uri>) = lifecycleScope.launch {
        var imported = 0
        var skipped = 0
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                val name = displayName(uri) ?: continue
                when (name.substringAfterLast('.', "").lowercase()) {
                    in GameStorage.SUPPORTED_EXTENSIONS -> {
                        contentResolver.openInputStream(uri)?.use { input ->
                            File(storage.romsDir, name).outputStream().use { input.copyTo(it) }
                        }
                        imported++
                    }
                    "zip" -> {
                        val found = importFromZip(uri)
                        if (found > 0) imported += found else skipped++
                    }
                    else -> skipped++
                }
            }
        }
        val msg = buildString {
            append("$imported jogo(s) importado(s)")
            if (skipped > 0) append(" • $skipped ignorado(s): use .gba, .gb, .gbc ou .zip com um desses dentro")
        }
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        refresh()
    }

    /**
     * Extrai de um .zip apenas os arquivos de jogo suportados (.gba/.gb/.gbc).
     * Usa só o nome do arquivo (sem as pastas internas do zip) para evitar
     * que um zip malicioso grave fora da pasta de jogos ("zip slip").
     */
    private fun importFromZip(uri: Uri): Int {
        var count = 0
        contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val fileName = File(entry.name).name
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && ext in GameStorage.SUPPORTED_EXTENSIONS) {
                        File(storage.romsDir, fileName).outputStream().use { zip.copyTo(it) }
                        count++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return count
    }

    private fun importSave(uri: Uri, rom: File) = lifecycleScope.launch {
        val bytes = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(this@MainActivity, "Arquivo de save vazio ou inválido", Toast.LENGTH_LONG).show()
            return@launch
        }
        withContext(Dispatchers.IO) { storage.writeSram(rom, bytes) }
        Toast.makeText(this@MainActivity, "Save importado para ${rom.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
    }

    private fun exportSave(uri: Uri, rom: File) = lifecycleScope.launch {
        val data = storage.readSram(rom)
        if (data == null) {
            Toast.makeText(this@MainActivity, "Esse jogo ainda não tem save", Toast.LENGTH_LONG).show()
            return@launch
        }
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
        }
        Toast.makeText(this@MainActivity, "Save exportado", Toast.LENGTH_SHORT).show()
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.replace('/', '_')

    private inner class GameAdapter(items: List<File>) :
        ArrayAdapter<File>(this@MainActivity, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_game, parent, false)
            val rom = getItem(position)!!
            val hasSave = storage.sramFile(rom).exists()
            view.findViewById<TextView>(R.id.title).text = rom.nameWithoutExtension
            view.findViewById<TextView>(R.id.subtitle).text =
                GameStorage.systemLabel(rom) + if (hasSave) "  •  com save" else ""
            return view
        }
    }
}
