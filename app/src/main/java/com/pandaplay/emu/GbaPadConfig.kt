package com.pandaplay.emu

import android.view.KeyEvent
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig

/**
 * Controle virtual na tela, no formato de um GBA:
 *   esquerda -> direcional + SELECT + L
 *   direita  -> A, B + START + R
 *
 * Os ids dos botões são os keycodes padrão de gamepad do Android,
 * enviados direto para o core (A = botão A do GBA).
 */
object GbaPadConfig {

    val LEFT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.Cross(CrossConfig(0)), // id 0 = MOTION_SOURCE_DPAD
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(
                2, 1f, 0f,
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_SELECT, label = "SELECT")
            ),
            SecondaryDialConfig.SingleButton(
                3, 1f, 0f,
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_L1, label = "L")
            )
        )
    )

    val RIGHT = RadialGamePadConfig(
        sockets = 12,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "A"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "B")
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(
                3, 1f, 0f,
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_R1, label = "R")
            ),
            SecondaryDialConfig.SingleButton(
                4, 1f, 0f,
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_START, label = "START")
            )
        )
    )
}
