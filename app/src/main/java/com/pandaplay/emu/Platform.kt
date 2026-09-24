package com.pandaplay.emu

import java.io.File

/**
 * Cada console suportado: qual core libretro roda ele, quais arquivos aceita,
 * como é o controle na tela e de onde vêm as capas.
 *
 * Para adicionar um console novo: inclua aqui, crie o layout em PadLayouts
 * e acrescente o core no script do GitHub Actions.
 */
enum class Platform(
    val label: String,
    val shortLabel: String,
    val extensions: Set<String>,
    /** Nome do arquivo do core em app/src/main/jniLibs/<abi>/ */
    val coreFile: String,
    val pad: PadLayouts.Layout,
    /** Repositório de capas do libretro-thumbnails no GitHub. */
    val thumbnailRepo: String,
    /** Código na API do Homebrew Hub (null = sem jogos grátis para esse console). */
    val homebrewCode: String? = null,
    /** Console 3D: usa filtro suave em vez de pixels nítidos. */
    val is3D: Boolean = false,
    /** Usa analógico: o analógico do controle físico vai direto para o jogo. */
    val analog: Boolean = false,
    /** Usa L2/R2/L3 no jogo: esses botões não viram atalhos do emulador. */
    val usesTriggers: Boolean = false,
    /** Tela de toque (Nintendo DS). */
    val touchScreen: Boolean = false,
    /** Configurações iniciais do core. */
    val coreOptions: List<Pair<String, String>> = emptyList(),
) {
    NES(
        "Nintendo (NES)", "NES", setOf("nes"),
        "libfceumm_libretro_android.so", PadLayouts.NES,
        "Nintendo_-_Nintendo_Entertainment_System", homebrewCode = "NES",
    ),
    SNES(
        "Super Nintendo", "SNES", setOf("sfc", "smc"),
        "libsnes9x_libretro_android.so", PadLayouts.SNES,
        "Nintendo_-_Super_Nintendo_Entertainment_System",
    ),
    N64(
        "Nintendo 64", "N64", setOf("n64", "z64", "v64"),
        "libmupen64plus_next_gles3_libretro_android.so", PadLayouts.N64,
        "Nintendo_-_Nintendo_64",
        is3D = true, analog = true, usesTriggers = true,
        coreOptions = listOf(
            "mupen64plus-43screensize" to "320x240",
            "mupen64plus-FrameDuping" to "True",
        ),
    ),
    GB(
        "Game Boy", "GB", setOf("gb"),
        "libmgba_libretro_android.so", PadLayouts.GAME_BOY,
        "Nintendo_-_Game_Boy", homebrewCode = "GB",
    ),
    GBC(
        "Game Boy Color", "GBC", setOf("gbc"),
        "libmgba_libretro_android.so", PadLayouts.GAME_BOY,
        "Nintendo_-_Game_Boy_Color", homebrewCode = "GBC",
    ),
    GBA(
        "Game Boy Advance", "GBA", setOf("gba"),
        "libmgba_libretro_android.so", PadLayouts.GBA,
        "Nintendo_-_Game_Boy_Advance", homebrewCode = "GBA",
    ),
    NDS(
        "Nintendo DS", "DS", setOf("nds"),
        "libdesmume_libretro_android.so", PadLayouts.NDS,
        "Nintendo_-_Nintendo_DS",
        touchScreen = true,
        coreOptions = listOf(
            "desmume_pointer_type" to "touch",
            "desmume_frameskip" to "1",
        ),
    ),
    SMS(
        "Master System", "SMS", setOf("sms"),
        "libgenesis_plus_gx_libretro_android.so", PadLayouts.SMS,
        "Sega_-_Master_System_-_Mark_III",
    ),
    GG(
        "Game Gear", "GG", setOf("gg"),
        "libgenesis_plus_gx_libretro_android.so", PadLayouts.SMS,
        "Sega_-_Game_Gear",
    ),
    MD(
        "Mega Drive", "MD", setOf("md", "gen", "smd"),
        "libgenesis_plus_gx_libretro_android.so", PadLayouts.MEGA_DRIVE,
        "Sega_-_Mega_Drive_-_Genesis",
    ),
    PSX(
        "PlayStation", "PS1", setOf("cue", "chd", "pbp", "m3u", "iso"),
        "libpcsx_rearmed_libretro_android.so", PadLayouts.PSX,
        "Sony_-_PlayStation",
        is3D = true, analog = true, usesTriggers = true,
    );

    companion object {
        fun of(file: File): Platform? {
            val ext = file.extension.lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }

        /**
         * Arquivos auxiliares que são importados mas não aparecem na biblioteca:
         * as trilhas .bin/.img de jogos de PlayStation (o jogo é aberto pelo .cue).
         */
        val COMPANION_EXTENSIONS = setOf("bin", "img", "sub")
    }
}
