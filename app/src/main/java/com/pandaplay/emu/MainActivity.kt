package com.pandaplay.emu

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pandaplay.emu.library.CardActions
import com.pandaplay.emu.library.CardAdapter
import com.pandaplay.emu.library.CardSize
import com.pandaplay.emu.library.Game
import com.pandaplay.emu.library.GameLibrary
import com.pandaplay.emu.library.GameScanner
import com.pandaplay.emu.library.SectionAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * Tela inicial: biblioteca estilo console.
 *   - "Início": seções (Continuar jogando, Favoritos, Mais jogados, uma linha por console)
 *   - "Todos" / um console / busca: grade de capas
 * Navegável por toque, mouse, teclado, controle Bluetooth e controle remoto da TV.
 */
class MainActivity : AppCompatActivity(), CardActions {

    private lateinit var storage: GameStorage
    private lateinit var libPrefs: LibraryPrefs
    private lateinit var library: GameLibrary
    private lateinit var content: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var searchBox: EditText
    private lateinit var filtersRow: LinearLayout
    private lateinit var cardSize: CardSize

    private var allGames: List<Game> = emptyList()
    private var coverJob: Job? = null
    private var scanJob: Job? = null
    private val cardAdapters = mutableListOf<CardAdapter>()

    /** O que está na tela: início (seções), todos os jogos ou um console. */
    private sealed class Mode(val label: String) {
        object Home : Mode("🏠 Início")
        object All : Mode("Todos")
        class ByPlatform(val platform: Platform) : Mode(platform.shortLabel)
    }

    private var mode: Mode = Mode.Home
    private var chips: List<Mode> = emptyList()

    /** Jogo selecionado ao importar/exportar um save ou aplicar patch. */
    private var pendingSaveTarget: File? = null

    /** Voltando da tela de permissão de arquivos: abrir o seletor de pasta. */
    private var pendingFolderPick = false

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

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    private val requestReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pickFolder.launch(null)
            else toastLong("Sem permissão para ler os arquivos, a pasta não pode ser usada.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        storage = GameStorage(this)
        libPrefs = LibraryPrefs(this)
        library = GameLibrary(storage, libPrefs)
        cardSize = computeCardSize()

        content = findViewById(R.id.content)
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
            override fun afterTextChanged(s: Editable?) = render()
        })
    }

    override fun onResume() {
        super.onResume()
        if (pendingFolderPick) {
            pendingFolderPick = false
            if (hasFilesPermission()) pickFolder.launch(null)
            else toastLong("Sem a permissão de arquivos, a pasta não pode ser usada.")
        }
        refresh()
        // Pasta monitorada: procura jogos novos ao abrir o app (no máximo a cada 1 minuto)
        if (libPrefs.folders().isNotEmpty() && System.currentTimeMillis() - libPrefs.lastScan() > 60_000) {
            rescanFolders(showSummary = false)
        }
    }

    // ---------------------------------------------------------------- tela

    /** Capas maiores na TV e em tablets. */
    private fun computeCardSize(): CardSize {
        val isTv = getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val widthDp = resources.configuration.screenWidthDp
        val coverDp = when {
            isTv -> 150
            widthDp >= 700 -> 140
            else -> 112
        }
        val d = resources.displayMetrics.density
        return CardSize((coverDp * d).toInt(), (coverDp * 4 / 3 * d).toInt())
    }

    private fun refresh() {
        lifecycleScope.launch {
            allGames = withContext(Dispatchers.IO) { library.loadAll() }
            buildChips()
            render()
            fetchMissingCovers()
        }
    }

    private fun buildChips() {
        val platforms = Platform.entries.filter { p -> allGames.any { it.platform == p } }
        chips = listOf(Mode.Home, Mode.All) + platforms.map { Mode.ByPlatform(it) }
        val current = mode
        if (current is Mode.ByPlatform && current.platform !in platforms) mode = Mode.Home

        filtersRow.removeAllViews()
        for (m in chips) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, filtersRow, false) as TextView
            chip.text = m.label
            chip.isSelected = isCurrent(m)
            chip.setOnClickListener {
                mode = m
                for (i in 0 until filtersRow.childCount) filtersRow.getChildAt(i).isSelected = isCurrent(chips[i])
                render()
            }
            filtersRow.addView(chip)
        }
    }

    private fun isCurrent(m: Mode): Boolean {
        val cur = mode
        return when {
            m is Mode.ByPlatform && cur is Mode.ByPlatform -> m.platform == cur.platform
            else -> m::class == cur::class
        }
    }

    private fun render() {
        cardAdapters.clear()
        val query = searchBox.text?.toString().orEmpty()

        if (allGames.isEmpty()) {
            showEmpty(getString(R.string.empty_library)); return
        }

        val m = mode
        if (query.isBlank() && m is Mode.Home) {
            val sections = library.sections(allGames)
            emptyView.visibility = View.GONE
            content.visibility = View.VISIBLE
            content.layoutManager = LinearLayoutManager(this)
            content.adapter = SectionAdapter(sections, cardSize, lifecycleScope, this) { cardAdapters += it }
            return
        }

        val base = when (m) {
            is Mode.ByPlatform -> allGames.filter { it.platform == m.platform }
            else -> allGames
        }
        val games = library.search(base, query)
        if (games.isEmpty()) { showEmpty("Nenhum jogo encontrado."); return }

        emptyView.visibility = View.GONE
        content.visibility = View.VISIBLE
        val cellPx = cardSize.coverWidthPx + (32 * resources.displayMetrics.density).toInt()
        val span = (resources.displayMetrics.widthPixels / cellPx).coerceAtLeast(2)
        content.layoutManager = GridLayoutManager(this, span)
        val adapter = CardAdapter(games, cardSize, lifecycleScope, this)
        cardAdapters += adapter
        content.adapter = adapter
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
        content.visibility = View.GONE
    }

    /** Baixa em segundo plano as capas que faltam e atualiza só os cards afetados. */
    private fun fetchMissingCovers() {
        coverJob?.cancel()
        coverJob = lifecycleScope.launch {
            for (g in allGames) {
                if (g.coverFile.exists() || libPrefs.coverMissing(g.file)) continue
                val got = withContext(Dispatchers.IO) { Covers.ensureCover(storage, libPrefs, g.file) }
                if (got) cardAdapters.forEach { it.refreshGame(g.id) }
            }
        }
    }

    // ---------------------------------------------------------------- ações dos cards

    override fun onPlay(game: Game) = play(game.file)

    override fun onOptions(game: Game) = showGameOptions(game)

    private fun play(rom: File) {
        val missing = storage.missingCueTracks(rom)
        if (missing.isNotEmpty()) {
            showError(
                "Faltam arquivos do CD",
                "Este jogo de PlayStation precisa também de: ${missing.joinToString()}.\n\n" +
                    "Coloque esses arquivos na mesma pasta do .cue."
            )
            return
        }
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_ROM_PATH, rom.absolutePath)
        )
    }

    private fun showGameOptions(game: Game) {
        val rom = game.file
        val fav = if (game.favorite) "☆ Remover dos favoritos" else "⭐ Favoritar"
        val remove = if (game.source == Game.Source.FOLDER) "Ocultar da biblioteca" else "Remover da biblioteca"
        val options = arrayOf(
            "▶ Jogar",
            fav,
            "Aplicar tradução (patch)",
            "Importar save (.sav/.srm)",
            "Exportar save",
            "Informações do jogo",
            remove,
        )
        AlertDialog.Builder(this)
            .setTitle(game.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> play(rom)
                    1 -> { libPrefs.toggleFavorite(rom); refresh() }
                    2 -> { pendingSaveTarget = rom; pickPatch.launch(arrayOf("*/*")) }
                    3 -> { pendingSaveTarget = rom; pickSave.launch(arrayOf("*/*")) }
                    4 -> { pendingSaveTarget = rom; exportSave.launch(rom.nameWithoutExtension + ".sav") }
                    5 -> showInfo(game)
                    6 -> if (game.source == Game.Source.FOLDER) confirmHide(game) else confirmDelete(rom)
                }
            }
            .show()
    }

    /** Mostra o CRC32 (conferir versão para traduções), local do arquivo e tempo jogado. */
    private fun showInfo(game: Game) = lifecycleScope.launch {
        val rom = game.file
        val crc = withContext(Dispatchers.IO) { fileCrc(rom) }
        val last = if (game.lastPlayed > 0)
            DateUtils.getRelativeTimeSpanString(game.lastPlayed).toString() else "nunca"
        AlertDialog.Builder(this@MainActivity)
            .setTitle(game.title)
            .setMessage(
                "Console: ${game.platform.label}\n" +
                    "Arquivo: ${rom.name}\n" +
                    "Tamanho: ${formatSize(rom.length())}\n" +
                    "Local: ${if (game.source == Game.Source.FOLDER) rom.parent else "dentro do app"}\n" +
                    "Tempo jogado: ${Game.formatPlayTime(game.playTimeMs)}\n" +
                    "Jogado por último: $last\n" +
                    "CRC32: $crc\n\n" +
                    "Confira o CRC32 com o informado na página da tradução antes de aplicar um patch."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** CRC32 lendo o arquivo aos poucos (funciona até com jogos de PS1 de 700 MB). */
    private fun fileCrc(file: File): String {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return Patcher.crcHex(crc.value)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }

    private fun confirmDelete(rom: File) {
        AlertDialog.Builder(this)
            .setTitle("Remover ${Game.cleanTitle(rom.nameWithoutExtension)}?")
            .setMessage("O jogo, o save e os save states serão apagados deste aparelho. Exporte o save antes se quiser guardar o progresso.")
            .setPositiveButton("Remover") { _, _ -> storage.deleteGame(rom); refresh() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmHide(game: Game) {
        AlertDialog.Builder(this)
            .setTitle("Ocultar ${game.title}?")
            .setMessage("O arquivo continua na sua pasta, só deixa de aparecer no PandaPlay. Para mostrar de novo: + Importar → Pastas monitoradas → Mostrar ocultos.")
            .setPositiveButton("Ocultar") { _, _ -> libPrefs.hide(game.id); refresh() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- importar e pastas

    private fun showImportMenu() {
        val folderCount = libPrefs.folders().size
        val items = arrayOf(
            "📁 Adicionar pasta de jogos (sem copiar)",
            "📂 Pastas monitoradas" + if (folderCount > 0) " ($folderCount)" else "",
            "🎮 Importar arquivos de jogos",
            "🧩 BIOS (PlayStation)",
        )
        AlertDialog.Builder(this)
            .setTitle("Adicionar jogos")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startAddFolder()
                    1 -> showFolders()
                    2 -> pickRoms.launch(arrayOf("*/*"))
                    3 -> showBiosHelp()
                }
            }
            .show()
    }

    private fun hasFilesPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Para ler os jogos direto da pasta (sem copiar), o Android pede a permissão
     * "Acesso a todos os arquivos". O PandaPlay só lê as pastas que você escolher.
     */
    private fun startAddFolder() {
        if (hasFilesPermission()) { pickFolder.launch(null); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Permitir acesso aos arquivos")
                .setMessage(
                    "Para jogar direto da sua pasta, sem copiar os jogos (importante para PS1 e DS, que são grandes), " +
                        "o Android pede a permissão \"Acesso a todos os arquivos\".\n\n" +
                        "Na próxima tela, ative a chave do PandaPlay e volte."
                )
                .setPositiveButton("Continuar") { _, _ ->
                    pendingFolderPick = true
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (e2: Exception) {
                            pendingFolderPick = false
                            showError(
                                "Não disponível neste aparelho",
                                "Este aparelho não tem a tela dessa permissão. Use \"Importar arquivos de jogos\"."
                            )
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            requestReadPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun onFolderPicked(uri: Uri) {
        val path = treeUriToPath(uri)
        if (path == null || !File(path).isDirectory) {
            showError(
                "Pasta não suportada",
                "Escolha uma pasta do armazenamento do aparelho ou do cartão SD. Pastas de nuvem (Drive, OneDrive) ainda não funcionam aqui."
            )
            return
        }
        libPrefs.addFolder(path)
        rescanFolders(showSummary = true)
    }

    /** Converte a pasta escolhida no seletor do Android para um caminho de arquivo. */
    private fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true))
            Environment.getExternalStorageDirectory().absolutePath
        else "/storage/$volume"
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    private fun rescanFolders(showSummary: Boolean) {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            if (showSummary) toastLong("🔎 Procurando jogos…")
            val before = libPrefs.scannedPaths()
            val found = withContext(Dispatchers.IO) { GameScanner.scan(libPrefs.folders()) }
            libPrefs.setScannedPaths(found)
            val newOnes = found - before
            refresh()

            if (showSummary) {
                val counts = GameScanner.countByPlatform(found)
                val lines = if (counts.isEmpty()) "Nenhum jogo encontrado nas pastas."
                else counts.entries.joinToString("\n") { "${it.key.label}: ${it.value}" } + "\n\nTotal: ${found.size} jogos"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Biblioteca atualizada")
                    .setMessage(lines)
                    .setPositiveButton("OK", null)
                    .show()
            } else if (newOnes.isNotEmpty()) {
                toastLong("${newOnes.size} jogo(s) novo(s) encontrado(s)")
            }
        }
    }

    private fun showFolders() {
        val folders = libPrefs.folders().sorted()
        val builder = AlertDialog.Builder(this).setTitle("Pastas monitoradas")
        if (folders.isEmpty()) {
            builder.setMessage("Nenhuma pasta ainda. Use \"Adicionar pasta de jogos\".")
                .setPositiveButton("Adicionar pasta") { _, _ -> startAddFolder() }
        } else {
            builder.setItems(folders.map { "📁 $it" }.toTypedArray()) { _, i -> confirmRemoveFolder(folders[i]) }
                .setPositiveButton("🔄 Procurar jogos novos") { _, _ -> rescanFolders(showSummary = true) }
                .setNeutralButton("Mostrar ocultos") { _, _ -> libPrefs.unhideAll(); refresh() }
        }
        builder.setNegativeButton("Fechar", null).show()
    }

    private fun confirmRemoveFolder(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Parar de monitorar esta pasta?")
            .setMessage("$path\n\nOs arquivos não são apagados. Os jogos dela só saem da biblioteca.")
            .setPositiveButton("Remover") { _, _ ->
                libPrefs.removeFolder(path)
                rescanFolders(showSummary = false)
            }
            .setNegativeButton("Cancelar", null)
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

}
