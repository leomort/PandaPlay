package com.pandaplay.emu.library

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pandaplay.emu.Covers
import com.pandaplay.emu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tamanho dos cards: maior na TV. */
class CardSize(val coverWidthPx: Int, val coverHeightPx: Int)

/** Ações ao tocar ou segurar um card. */
interface CardActions {
    fun onPlay(game: Game)
    fun onOptions(game: Game)
}

/**
 * Cards de jogos (capa, console, nome, tempo jogado).
 * Funciona com toque e com controle: OK joga, Y/X/Menu abre as opções.
 */
class CardAdapter(
    private var games: List<Game>,
    private val size: CardSize,
    private val scope: CoroutineScope,
    private val actions: CardActions,
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val coverBox: View = view.findViewById(R.id.coverBox)
        val cover: ImageView = view.findViewById(R.id.cover)
        val coverTitle: TextView = view.findViewById(R.id.coverTitle)
        val badge: TextView = view.findViewById(R.id.badge)
        val fav: TextView = view.findViewById(R.id.fav)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    override fun getItemCount() = games.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        val h = Holder(view)
        h.coverBox.layoutParams = h.coverBox.layoutParams.apply {
            width = size.coverWidthPx; height = size.coverHeightPx
        }
        h.title.layoutParams = h.title.layoutParams.apply { width = size.coverWidthPx }
        h.subtitle.layoutParams = h.subtitle.layoutParams.apply { width = size.coverWidthPx }
        // Efeito de destaque ao receber foco (navegação por controle/TV)
        view.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val g = games[position]
        h.title.text = g.title
        h.badge.text = g.platform.shortLabel
        h.fav.visibility = if (g.favorite) View.VISIBLE else View.GONE
        h.subtitle.text = when {
            g.playTimeMs >= 60_000 -> "⏱ " + Game.formatPlayTime(g.playTimeMs)
            g.hasSave -> "💾 com save"
            else -> g.platform.label
        }

        // Capa: mostra o nome no lugar enquanto não existe imagem
        h.cover.setImageBitmap(null)
        h.coverTitle.text = g.title
        h.coverTitle.visibility = View.VISIBLE
        h.cover.tag = g.id
        if (g.coverFile.exists()) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { Covers.load(g.coverFile) }
                if (h.cover.tag == g.id && bmp != null) {
                    h.cover.setImageBitmap(bmp)
                    h.coverTitle.visibility = View.GONE
                }
            }
        }

        h.itemView.setOnClickListener { actions.onPlay(g) }
        h.itemView.setOnLongClickListener { actions.onOptions(g); true }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            val isOptionsKey = keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                keyCode == KeyEvent.KEYCODE_BUTTON_X
            if (isOptionsKey && event.action == KeyEvent.ACTION_UP) { actions.onOptions(g); true }
            else isOptionsKey
        }
    }

    /** Atualiza só o card de um jogo (ex.: quando a capa termina de baixar). */
    fun refreshGame(id: String) {
        val i = games.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i)
    }
}

/** Tela inicial: uma seção por linha, cada uma com seus cards rolando para o lado. */
class SectionAdapter(
    private val sections: List<GameLibrary.Section>,
    private val size: CardSize,
    private val scope: CoroutineScope,
    private val actions: CardActions,
    /** Chamado a cada linha criada, para o app atualizar capas depois. */
    private val onRowAdapter: (CardAdapter) -> Unit,
) : RecyclerView.Adapter<SectionAdapter.Holder>() {

    private val pool = RecyclerView.RecycledViewPool()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sectionTitle)
        val row: RecyclerView = view.findViewById(R.id.row)
    }

    override fun getItemCount() = sections.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_section, parent, false)
        val h = Holder(view)
        h.row.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        h.row.setRecycledViewPool(pool)
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val section = sections[position]
        h.title.text = section.title
        val adapter = CardAdapter(section.games, size, scope, actions)
        h.row.adapter = adapter
        onRowAdapter(adapter)
    }
}
