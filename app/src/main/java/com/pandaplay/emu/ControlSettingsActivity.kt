package com.pandaplay.emu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela ⚙️ Controles. Abre pela biblioteca ou pelo botão ⚙ dentro do jogo.
 * No topo escolhe-se "Todos os consoles" ou um console específico.
 */
class ControlSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATFORM = "platform"
    }

    private lateinit var settings: ControlSettings
    private lateinit var listView: ListView
    private lateinit var scopesRow: LinearLayout
    private lateinit var hint: TextView

    /** null = todos os consoles */
    private var scope: Platform? = null

    /** Linhas da lista: um título de grupo ou uma opção. */
    private sealed class Row {
        class Header(val text: String) : Row()
        class Item(val setting: ControlSettings.Setting) : Row()
        object Reset : Row()
    }

    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controls)
        settings = ControlSettings(this)
        listView = findViewById(R.id.settingsList)
        scopesRow = findViewById(R.id.scopes)
        hint = findViewById(R.id.scopeHint)

        scope = intent.getStringExtra(EXTRA_PLATFORM)?.let { name ->
            Platform.entries.firstOrNull { it.name == name }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            when (val row = rows[position]) {
                is Row.Item -> choose(row.setting)
                is Row.Reset -> confirmReset()
                else -> Unit
            }
        }

        buildScopes()
        render()
    }

    private fun buildScopes() {
        scopesRow.removeAllViews()
        val all: List<Platform?> = listOf(null) + Platform.entries
        for (p in all) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, scopesRow, false) as TextView
            chip.text = p?.label ?: "Todos os consoles"
            chip.isSelected = p == scope
            chip.setOnClickListener {
                scope = p
                for (i in 0 until scopesRow.childCount) scopesRow.getChildAt(i).isSelected = all[i] == p
                render()
            }
            scopesRow.addView(chip)
        }
    }

    private fun render() {
        val p = scope
        hint.text = if (p == null)
            "Valem para todos os consoles. Escolha um console acima para personalizar só ele."
        else
            "Personalizando o ${p.label}. O que não for alterado aqui segue \"Todos os consoles\"."

        val list = mutableListOf<Row>()
        var group = ""
        for (s in ControlSettings.Setting.entries) {
            if (s.group != group) { group = s.group; list += Row.Header(group) }
            list += Row.Item(s)
        }
        list += Row.Reset
        rows = list
        listView.adapter = Adapter()
    }

    private fun choose(setting: ControlSettings.Setting) {
        val p = scope
        val current = settings.get(setting, p)
        val labels = setting.options.map { it.second }.toTypedArray()
        val checked = setting.options.indexOfFirst { it.first == current }
        val builder = AlertDialog.Builder(this)
            .setTitle(setting.title + (p?.let { " · ${it.shortLabel}" } ?: ""))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.set(setting, p, setting.options[which].first)
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancelar", null)
        if (p != null && settings.isCustom(setting, p)) {
            builder.setNeutralButton("Usar o de todos") { _, _ -> settings.clear(setting, p); render() }
        }
        builder.show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Restaurar controles?")
            .setMessage("Todas as configurações de controle, de todos os consoles, voltam ao padrão.")
            .setPositiveButton("Restaurar") { _, _ -> settings.resetAll(); render() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = rows[position] !is Row.Header
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is Row.Header) {
                return TextView(this@ControlSettingsActivity).apply {
                    text = row.text
                    setTextColor(0xFFF2B233.toInt())
                    textSize = 14f
                    setPadding(8, 24, 8, 4)
                }
            }
            val view = LayoutInflater.from(this@ControlSettingsActivity)
                .inflate(R.layout.item_game, parent, false)
            view.findViewById<ImageView>(R.id.cover).visibility = View.GONE
            val title = view.findViewById<TextView>(R.id.title)
            val subtitle = view.findViewById<TextView>(R.id.subtitle)
            when (row) {
                is Row.Item -> {
                    val s = row.setting
                    val p = scope
                    title.text = s.title
                    val value = s.label(settings.get(s, p))
                    subtitle.text = if (p != null && settings.isCustom(s, p)) "$value  •  personalizado" else value
                }
                is Row.Reset -> {
                    title.text = "↺ Restaurar padrão"
                    subtitle.text = "Volta todas as configurações de controle ao original"
                }
                else -> Unit
            }
            return view
        }
    }
}
