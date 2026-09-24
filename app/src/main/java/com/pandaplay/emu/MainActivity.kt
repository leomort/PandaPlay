package com.pandaplay.emu

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Biblioteca de jogos, organizada por plataforma.
 * Funciona com toque, mouse, teclado, controle Bluetooth e controle remoto da TV
 * (todos os botões e a lista são navegáveis pelo direcional).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var storage: GameStorage
    private lateinit var libPrefs: LibraryPrefs
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var searchBox: EditText
    private lateinit var filtersRow: LinearLayout

    private var allGames: List<File> = emptyList()
    private var games: List<File> = emptyList()
    private var filter: Filter = Filter.All
    private var filterPlatform: Platform? = null
    private var coverJob: Job? = null

    /** Filtros da biblioteca: gerais + um por plataforma (só aparecem as que têm jogos). */
    private sealed class Filter(val label: String) {
        object All : Filter("Todos")
        object Recent : Filter("🕘 Recentes")
        object Favorites : Filter("⭐ Favoritos")
        class ByPlatform(val platform: Platform) : Filter(platform.label)
    }

    private var chips: List<Filter> = emptyList()

    /** Jogo selecionado ao importar/exportar um save ou aplicar patch. */
    private var pendingSaveTarget: File? = null

    // Usa o seletor de arquivos do sistema (SAF): não precisa pedir permissão de armazenamento.
    private val pickRoms =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importRoms(uris)
        }

    private val pickBios =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importBios(uris)
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
        libPrefs = LibraryPrefs(this)

        listView = findViewById(R.id.gameList)
        emptyView = findViewById(R.id.emptyView)
        searchBox = findViewById(R.id.search)
        filtersRow = findViewById(R.id.filters)

        findViewById<View>(R.id.btnImport).setOnClickListener { showImportMenu() }
        findViewById<View>(R.id.btnStore).setOnClickListener {
            startActivity(Intent(this, StoreActivity::class.java))
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        listView.setOnItemClickListener { _, _, position, _ -> play(games[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showGameOptions(games[position]); true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showImportMenu() {
        AlertDialog.Builder(this)
            .setTitle("Importar")
            .setItems(arrayOf("🎮 Jogos", "🧩 BIOS (PlayStation e outros)")) { _, which ->
                if (which == 0) pickRoms.launch(arrayOf("*/*")) else showBiosHelp()
            }
            .show()
    }

    private fun showBiosHelp() {
        AlertDialog.Builder(this)
            .setTitle("BIOS")
            .setMessage(
                "Alguns consoles funcionam melhor com a BIOS original, extraída do seu próprio aparelho.\n\n" +
                    "• PlayStation: scph5501.bin (EUA), scph5502.bin (Europa) ou scph5500.bin (Japão). " +
                    "Sem ela o PandaPlay usa uma BIOS substituta, que roda a maioria dos jogos.\n\n" +
                    "Os outros consoles do app não precisam de BIOS."
            )
            .setPositiveButton("Escolher arquivos") { _, _ -> pickBios.launch(arrayOf("*/*")) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun importBios(uris: List<Uri>) = lifecycleScope.launch {
        val names = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val name = displayName(uri) ?: return@mapNotNull null
                contentResolver.openInputStream(uri)?.use { input ->
                    File(storage.systemDir, GameStorage.safeName(name)).outputStream().use { input.copyTo(it) }
                }
                name
            }
        }
        toastLong(if (names.isEmpty()) "Nenhum arquivo importado" else "BIOS importada: ${names.joinToString()}")
    }

    private fun buildFilterChips() {
        val platformsWithGames = Platform.entries.filter { p -> allGames.any { Platform.of(it) == p } }
        val newChips = listOf(Filter.All, Filter.Recent, Filter.Favorites) +
            platformsWithGames.map { Filter.ByPlatform(it) }

        // Se a plataforma selecionada ficou sem jogos, volta para "Todos"
        if (filterPlatform != null && filterPlatform !in platformsWithGames) {
            filter = Filter.All; filterPlatform = null
        }
        chips = newChips
        filtersRow.removeAllViews()
        for (f in chips) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, filtersRow, false) as TextView
            chip.text = f.label
            chip.isSelected = isCurrent(f)
            chip.setOnClickListener {
                filter = f
                filterPlatform = (f as? Filter.ByPlatform)?.platform
                for (i in 0 until filtersRow.childCount) {
                    filtersRow.getChildAt(i).isSelected = isCurrent(chips[i])
                }
                applyFilter()
            }
            filtersRow.addView(chip)
        }
    }

    private fun isCurrent(f: Filter): Boolean = when (f) {
        is Filter.ByPlatform -> filterPlatform == f.platform
        else -> filterPlatform == null && f::class == filter::class
    }

    private fun refresh() {
        allGames = storage.listGames()
        buildFilterChips()
        applyFilter()
        fetchMissingCovers()
    }

    private fun applyFilter() {
        val query = searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
        var list = allGames.filter { query.isEmpty() || it.nameWithoutExtension.lowercase().contains(query) }
        list = when (val f = filter) {
            is Filter.All -> list
            is Filter.Favorites -> list.filter { libPrefs.isFavorite(it) }
            is Filter.Recent -> list.filter { libPrefs.lastPlayed(it) > 0 }
                .sortedByDescending { libPrefs.lastPlayed(it) }
            is Filter.ByPlatform -> list.filter { Platform.of(it) == f.platform }
        }
        games = list

        val emptyLibrary = allGames.isEmpty()
        emptyView.text = when {
            emptyLibrary -> getString(R.string.empty_library)
            filter is Filter.Favorites && query.isEmpty() -> "Nenhum favorito ainda.\nSegure o dedo em um jogo e escolha \"Favoritar\"."
            filter is Filter.Recent && query.isEmpty() -> "Você ainda não jogou nada por aqui."
            else -> "Nenhum jogo encontrado."
        }
        emptyView.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (games.isEmpty()) View.GONE else View.VISIBLE
        listView.adapter = GameAdapter(games)
    }

    /** Baixa em segundo plano as capas que ainda faltam (uma de cada vez, sem travar a tela). */
    private fun fetchMissingCovers() {
        coverJob?.cancel()
        coverJob = lifecycleScope.launch {
            for (rom in allGames) {
                if (storage.coverFile(rom).exists() || libPrefs.coverMissing(rom)) continue
                val got = withContext(Dispatchers.IO) { Covers.ensureCover(storage, libPrefs, rom) }
                if (got) (listView.adapter as? GameAdapter)?.notifyDataSetChanged()
            }
        }
    }

    private fun play(rom: File) {
        val missing = storage.missingCueTracks(rom)
        if (missing.isNotEmpty()) {
            showError(
                "Faltam arquivos do CD",
                "Este jogo de PlayStation precisa também de: ${missing.joinToString()}.\n\n" +
                    "Toque em \"+ Importar\" e selecione esses arquivos junto com o .cue."
            )
            return
        }
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_ROM_PATH, rom.absolutePath)
        )
    }

    private fun showGameOptions(rom: File) {
        val fav = if (libPrefs.isFavorite(rom)) "☆ Remover dos favoritos" else "⭐ Favoritar"
        val options = arrayOf(
            "Jogar",
            fav,
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
                    1 -> { libPrefs.toggleFavorite(rom); applyFilter() }
                    2 -> { pendingSaveTarget = rom; pickPatch.launch(arrayOf("*/*")) }
                    3 -> { pendingSaveTarget = rom; pickSave.launch(arrayOf("*/*")) }
                    4 -> { pendingSaveTarget = rom; exportSave.launch(rom.nameWithoutExtension + ".sav") }
                    5 -> showInfo(rom)
                    6 -> confirmDelete(rom)
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
                    in storage.importableExtensions -> {
                        // Usa só o nome (sem mudar), pois o .cue do PS1 procura as trilhas pelo nome exato
                        val target = File(storage.romsDir, File(name).name)
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        if (Platform.of(target) != null) imported++
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
            if (skipped > 0) append(" • $skipped ignorado(s): formato não suportado")
        }
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun importFromZip(uri: Uri): Int =
        contentResolver.openInputStream(uri)?.use { storage.extractGamesFromZip(it).size } ?: 0

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
            val star = if (libPrefs.isFavorite(rom)) "⭐ " else ""
            view.findViewById<TextView>(R.id.title).text = star + rom.nameWithoutExtension

            val details = mutableListOf(GameStorage.systemLabel(rom))
            if (storage.sramFile(rom).exists()) details += "com save"
            val last = libPrefs.lastPlayed(rom)
            if (last > 0) details += "jogado " + DateUtils.getRelativeTimeSpanString(last).toString().lowercase()
            view.findViewById<TextView>(R.id.subtitle).text = details.joinToString("  •  ")

            val cover = view.findViewById<ImageView>(R.id.cover)
            val coverFile = storage.coverFile(rom)
            cover.tag = coverFile.absolutePath
            cover.setImageBitmap(null)
            if (coverFile.exists()) {
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { Covers.load(coverFile) }
                    if (cover.tag == coverFile.absolutePath) cover.setImageBitmap(bmp)
                }
            }
            return view
        }
    }
}
