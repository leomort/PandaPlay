package com.pandaplay.emu

import android.view.KeyEvent
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig

/**
 * Controles virtuais na tela para cada console.
 *
 * Os ids são os botões do "RetroPad" do libretro (keycodes de gamepad do Android):
 *   BUTTON_A = direita · BUTTON_B = baixo · BUTTON_X = cima · BUTTON_Y = esquerda
 * Cada core traduz isso para o console. Ex.: no N64, A do console = BUTTON_B e B = BUTTON_Y;
 * no Mega Drive, A = BUTTON_Y, B = BUTTON_B e C = BUTTON_A.
 *
 * Posição dos botões extras (índice): começa às 3h e gira no sentido anti-horário, 30° por índice.
 */
object PadLayouts {

    class Layout(val left: RadialGamePadConfig, val right: RadialGamePadConfig)

    private const val DPAD = 0         // GLRetroView.MOTION_SOURCE_DPAD
    private const val ANALOG_LEFT = 1  // GLRetroView.MOTION_SOURCE_ANALOG_LEFT
    private const val ANALOG_RIGHT = 2 // GLRetroView.MOTION_SOURCE_ANALOG_RIGHT

    private fun btn(id: Int, label: String) = ButtonConfig(id = id, label = label)

    private fun extra(index: Int, id: Int, label: String) =
        SecondaryDialConfig.SingleButton(index, 1f, 0f, btn(id, label))

    private val SELECT = KeyEvent.KEYCODE_BUTTON_SELECT
    private val START = KeyEvent.KEYCODE_BUTTON_START
    private val A = KeyEvent.KEYCODE_BUTTON_A
    private val B = KeyEvent.KEYCODE_BUTTON_B
    private val X = KeyEvent.KEYCODE_BUTTON_X
    private val Y = KeyEvent.KEYCODE_BUTTON_Y
    private val L1 = KeyEvent.KEYCODE_BUTTON_L1
    private val R1 = KeyEvent.KEYCODE_BUTTON_R1
    private val L2 = KeyEvent.KEYCODE_BUTTON_L2
    private val R2 = KeyEvent.KEYCODE_BUTTON_R2

    private fun leftCross(vararg extras: SecondaryDialConfig) = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Cross(CrossConfig(DPAD)),
        secondaryDials = extras.toList(),
    )

    private fun rightButtons(buttons: List<ButtonConfig>, vararg extras: SecondaryDialConfig) =
        RadialGamePadConfig(
            sockets = 12,
            primaryDial = PrimaryDialConfig.PrimaryButtons(buttons),
            secondaryDials = extras.toList(),
        )

    /** NES, Game Boy e Game Boy Color: direcional, A, B, SELECT, START. */
    val NES = Layout(
        leftCross(extra(2, SELECT, "SELECT")),
        rightButtons(listOf(btn(A, "A"), btn(B, "B")), extra(4, START, "START")),
    )

    val GAME_BOY = NES

    val GBA = Layout(
        leftCross(extra(2, SELECT, "SELECT"), extra(3, L1, "L")),
        rightButtons(listOf(btn(A, "A"), btn(B, "B")), extra(3, R1, "R"), extra(4, START, "START")),
    )

    /** SNES e DS: A (direita), X (cima), Y (esquerda), B (baixo). */
    val SNES = Layout(
        leftCross(extra(2, SELECT, "SELECT"), extra(3, L1, "L")),
        rightButtons(
            listOf(btn(A, "A"), btn(X, "X"), btn(Y, "Y"), btn(B, "B")),
            extra(3, R1, "R"), extra(4, START, "START"),
        ),
    )

    val NDS = SNES

    /** Master System e Game Gear: botões 1 e 2. */
    val SMS = Layout(
        leftCross(),
        rightButtons(listOf(btn(A, "2"), btn(B, "1")), extra(4, START, "START")),
    )

    /** Mega Drive (3 botões): A, B, C. */
    val MEGA_DRIVE = Layout(
        leftCross(),
        rightButtons(listOf(btn(A, "C"), btn(B, "B"), btn(Y, "A")), extra(4, START, "START")),
    )

    /** PlayStation: ○ △ □ ✕ + L1/L2/R1/R2. */
    val PSX = Layout(
        leftCross(extra(2, SELECT, "SELECT"), extra(3, L1, "L1"), extra(4, L2, "L2")),
        rightButtons(
            listOf(btn(A, "○"), btn(X, "△"), btn(Y, "□"), btn(B, "✕")),
            extra(2, R2, "R2"), extra(3, R1, "R1"), extra(4, START, "START"),
        ),
    )

    /** Nintendo 64: analógico, A, B, botões C (no segundo analógico), Z, L, R. */
    val N64 = Layout(
        RadialGamePadConfig(
            sockets = 12,
            primaryDial = PrimaryDialConfig.Stick(ANALOG_LEFT),
            secondaryDials = listOf(extra(2, L2, "Z"), extra(3, L1, "L")),
        ),
        RadialGamePadConfig(
            sockets = 12,
            primaryDial = PrimaryDialConfig.PrimaryButtons(listOf(btn(B, "A"), btn(Y, "B"))),
            secondaryDials = listOf(
                extra(3, R1, "R"),
                extra(4, START, "START"),
                SecondaryDialConfig.Stick(8, 2, 1f, 0f, ANALOG_RIGHT, contentDescription = "C"),
            ),
        ),
    )
}
