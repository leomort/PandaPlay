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
        val options = arrayOf("Jogar", "Importar save (.sav/.srm)", "Exportar save", "Remover da biblioteca")
        AlertDialog.Builder(this)
            .setTitle(rom.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> play(rom)
                    1 -> { pendingSaveTarget = rom; pickSave.launch(arrayOf("*/*")) }
                    2 -> { pendingSaveTarget = rom; exportSave.launch(rom.nameWithoutExtension + ".sav") }
                    3 -> confirmDelete(rom)
                }
            }
            .show()
    }

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
