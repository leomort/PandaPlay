package com.pandaplay.emu

import android.content.Context

/**
 * Configurações de controle. Cada opção pode valer para todos os consoles
 * ou ser personalizada para um console específico (o console tem prioridade).
 */
class ControlSettings(context: Context) {

    enum class Setting(
        val key: String,
        val title: String,
        val group: String,
        val options: List<Pair<String, String>>,
        val default: String,
    ) {
        DPAD(
            "dpad", "Direcional na tela", "Controle na tela",
            listOf("default" to "Padrão do console", "cross" to "Setas (direcional)", "stick" to "Analógico"),
            "default",
        ),
        SIZE(
            "size", "Tamanho do controle", "Controle na tela",
            listOf("small" to "Pequeno", "medium" to "Médio", "large" to "Grande"),
            "medium",
        ),
        OPACITY(
            "opacity", "Transparência (celular deitado)", "Controle na tela",
            listOf("30" to "Bem transparente (30%)", "50" to "50%", "70" to "70%", "100" to "Sem transparência"),
            "70",
        ),
        EDGE(
            "edge", "Distância das bordas", "Controle na tela",
            listOf("near" to "Perto", "medium" to "Médio", "far" to "Longe"),
            "near",
        ),
        HEIGHT(
            "height", "Altura do controle (celular deitado)", "Controle na tela",
            listOf("low" to "Embaixo", "mid" to "Um pouco acima", "high" to "No meio"),
            "low",
        ),
        HEIGHT_PORTRAIT(
            "height_portrait", "Altura do controle (celular em pé)", "Controle na tela",
            listOf(
                "low" to "Embaixo",
                "mid" to "Um pouco acima",
                "high" to "Mais acima",
                "top" to "Perto do jogo",
            ),
            "low",
        ),
        HAPTIC(
            "haptic", "Vibrar ao tocar", "Controle na tela",
            listOf("on" to "Sim", "off" to "Não"),
            "on",
        ),
        SHOW(
            "show", "Mostrar controle na tela", "Controle na tela",
            listOf(
                "auto" to "Automático (some com controle Bluetooth)",
                "always" to "Sempre",
                "never" to "Nunca",
            ),
            "auto",
        ),
        FACE(
            "face", "Botões do controle Bluetooth", "Controle físico",
            listOf(
                "position" to "Pela posição (estilo Nintendo)",
                "label" to "Pelo nome escrito no botão (A é A)",
            ),
            "position",
        ),
        FAST(
            "fast", "Velocidade do acelerar", "Geral",
            listOf("2" to "2x", "3" to "3x", "4" to "4x"),
            "3",
        );

        fun label(value: String) = options.firstOrNull { it.first == value }?.second ?: value
    }

    private val prefs = context.getSharedPreferences("controls", Context.MODE_PRIVATE)

    private fun k(setting: Setting, platform: Platform?) =
        (platform?.name ?: "ALL") + "_" + setting.key

    /** Valor efetivo: do console, senão o de "Todos", senão o padrão. */
    fun get(setting: Setting, platform: Platform?): String =
        platform?.let { prefs.getString(k(setting, it), null) }
            ?: prefs.getString(k(setting, null), null)
            ?: setting.default

    /** O console tem um valor próprio (diferente de "Todos")? */
    fun isCustom(setting: Setting, platform: Platform) = prefs.contains(k(setting, platform))

    fun set(setting: Setting, platform: Platform?, value: String) {
        prefs.edit().putString(k(setting, platform), value).apply()
    }

    fun clear(setting: Setting, platform: Platform) {
        prefs.edit().remove(k(setting, platform)).apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }
}
