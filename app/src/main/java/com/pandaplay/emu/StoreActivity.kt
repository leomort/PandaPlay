package com.pandaplay.emu

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Aba "Jogos grátis": lista jogos independentes do Homebrew Hub por plataforma,
 * com busca e paginação. Um toque baixa o jogo direto para a biblioteca.
 */
class StoreActivity : AppCompatActivity() {

    private lateinit var storage: GameStorage
    private lateinit var listView: ListView
    private lateinit var status: TextView
    private lateinit var searchBox: EditText
    private lateinit var platformsRow: LinearLayout

    private val storePlatforms = Platform.entries.filter { it.homebrewCode != null }
    private var platform = Platform.GB
    private var query = ""
    private var page = 1
    private var pageTotal = 1
    private var total = 0
    private var loading = false
    private var loadJob: Job? = null

    private val items = mutableListOf<HomebrewHub.Entry>()
    private val adapter = StoreAdapter()
    private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_store)
        storage = GameStorage(this)

        listView = findViewById(R.id.storeList)
        status = findViewById(R.id.status)
        searchBox = findViewById(R.id.search)
        platformsRow = findViewById(R.id.platforms)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < items.size) showDetails(items[position]) else loadNextPage()
        }

        searchBox.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                query = searchBox.text.toString()
                hideKeyboard()
                reload()
                true
            } else false
        }

        buildPlatformChips()
        reload()
    }

    private fun buildPlatformChips() {
        platformsRow.removeAllViews()
        for (p in storePlatforms) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, platformsRow, false) as TextView
            chip.text = p.label
            chip.isSelected = p == platform
            chip.setOnClickListener {
                if (platform == p) return@setOnClickListener
                platform = p
                for (i in 0 until platformsRow.childCount) {
                    platformsRow.getChildAt(i).isSelected = storePlatforms[i] == p
                }
                reload()
            }
            platformsRow.addView(chip)
        }
    }

    private fun reload() {
        loadJob?.cancel()
        loading = false
        items.clear()
        page = 1
        pageTotal = 1
        adapter.notifyDataSetChanged()
        loadPage(1)
    }

    private fun loadNextPage() {
        if (!loading && page < pageTotal) loadPage(page + 1)
    }

    private fun loadPage(target: Int) {
        loading = true
        status.text = "Carregando…"
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { HomebrewHub.search(platform, query, target) }
            loading = false
            if (result == null) {
                status.text = "Não foi possível conectar ao Homebrew Hub. Verifique sua internet e toque em um filtro para tentar de novo."
                return@launch
            }
            page = result.page
            pageTotal = result.pageTotal
            total = result.total
            items += result.entries
            status.text = if (total == 0) "Nenhum jogo encontrado." else "$total jogos de ${platform.label}"
            adapter.notifyDataSetChanged()
        }
    }

    // ---------------------------------------------------------------- detalhes e download

    private fun installedFile(e: HomebrewHub.Entry): File? {
        val base = GameStorage.safeName(e.title)
        return storage.listGames().firstOrNull { it.nameWithoutExtension == base }
    }

    private fun showDetails(e: HomebrewHub.Entry) {
        val installed = installedFile(e)
        val msg = buildString {
            append("Autor: ").append(e.developer).append('\n')
            append("Plataforma: ").append(e.platform).append('\n')
            if (e.tags.isNotEmpty()) append("Categorias: ").append(e.tags.joinToString()).append('\n')
            if (e.license.isNotBlank()) append("Licença: ").append(e.license).append('\n')
            append("\nPágina: https://hh.gbdev.io/game/").append(e.slug)
        }
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setMessage(msg)
            .setPositiveButton(if (installed != null) "Já está na biblioteca" else "⬇ Baixar") { _, _ ->
                if (installed == null) download(e)
            }
            .setNegativeButton("Voltar", null)
            .show()
    }

    private fun download(e: HomebrewHub.Entry) {
        status.text = "Baixando ${e.title}…"
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { downloadToLibrary(e) }
            if (saved == null) {
                status.text = "Falha ao baixar ${e.title}."
                Toast.makeText(this@StoreActivity, "Não foi possível baixar. Tente de novo.", Toast.LENGTH_LONG).show()
                return@launch
            }
            status.text = "✅ ${e.title} adicionado à biblioteca"
            adapter.notifyDataSetChanged()
            Toast.makeText(this@StoreActivity, "${e.title} está na sua biblioteca!", Toast.LENGTH_SHORT).show()
        }
    }

    /** Baixa o jogo (e o screenshot como capa). Retorna o arquivo salvo, ou null se falhar. */
    private fun downloadToLibrary(e: HomebrewHub.Entry): File? {
        val bytes = Net.get(e.romUrl) ?: return null
        val ext = e.romFile.substringAfterLast('.', "").lowercase()

        val rom: File = if (ext == "zip") {
            val extracted = storage.extractGamesFromZip(bytes.inputStream())
            val first = extracted.firstOrNull() ?: return null
            // Renomeia para o título do jogo, para ficar bonito na biblioteca
            val target = File(storage.romsDir, GameStorage.safeName(e.title) + "." + first.extension)
            if (first.renameTo(target)) target else first
        } else {
            File(storage.romsDir, GameStorage.safeName(e.title) + "." + ext).also { it.writeBytes(bytes) }
        }

        e.screenshotUrl?.let { url ->
            Net.get(url, maxBytes = 4 * 1024 * 1024)?.let { storage.coverFile(rom).writeBytes(it) }
        }
        return rom
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchBox.windowToken, 0)
        listView.requestFocus()
    }

    // ---------------------------------------------------------------- lista

    private inner class StoreAdapter : BaseAdapter() {

        override fun getCount() = items.size + if (page < pageTotal) 1 else 0
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@StoreActivity).inflate(R.layout.item_game, parent, false)
            val title = view.findViewById<TextView>(R.id.title)
            val subtitle = view.findViewById<TextView>(R.id.subtitle)
            val cover = view.findViewById<ImageView>(R.id.cover)

            if (position >= items.size) { // item "carregar mais"
                title.text = if (loading) "Carregando…" else "➕ Carregar mais jogos"
                subtitle.text = "Página $page de $pageTotal"
                cover.tag = null
                cover.setImageBitmap(null)
                return view
            }

            val e = items[position]
            val done = if (installedFile(e) != null) "✅ " else ""
            title.text = done + e.title
            subtitle.text = listOf(e.developer, e.tags.take(3).joinToString()).filter { it.isNotBlank() }
                .joinToString("  •  ")

            cover.tag = e.slug
            cover.setImageBitmap(null)
            val url = e.screenshotUrl
            if (url != null) {
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) { thumbnail(e.slug, url) }
                    val bmp = file?.let { withContext(Dispatchers.IO) { Covers.load(it) } }
                    if (cover.tag == e.slug) cover.setImageBitmap(bmp)
                }
            }
            return view
        }
    }

    /** Miniaturas da loja ficam no cache do app (o Android limpa sozinho se faltar espaço). */
    private fun thumbnail(slug: String, url: String): File? {
        thumbCache[slug]?.let { return it }
        val dir = File(cacheDir, "store-thumbs").apply { mkdirs() }
        val file = File(dir, "$slug.img")
        if (!file.exists()) {
            val bytes = Net.get(url, maxBytes = 4 * 1024 * 1024) ?: return null
            file.writeBytes(bytes)
        }
        thumbCache[slug] = file
        return file
    }
}
