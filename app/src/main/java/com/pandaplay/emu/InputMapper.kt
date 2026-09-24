package com.pandaplay.emu

import android.view.InputDevice
import android.view.KeyEvent

/** Ações do emulador (não do jogo) que podem vir do controle ou do teclado. */
enum class Hotkey { FAST_FORWARD, SAVE_STATE, LOAD_STATE }

object InputMapper {

    /**
     * Atalhos no controle Bluetooth. O GBA não usa L2/R2/L3, então ficam livres:
     *   R2 -> liga/desliga acelerar (ótimo para upar e caçar shiny)
     *   L2 -> salvar estado
     *   L3 (apertar o analógico esquerdo) -> carregar estado
     */
    private val gamepadHotkeys = mapOf(
        KeyEvent.KEYCODE_BUTTON_R2 to Hotkey.FAST_FORWARD,
        KeyEvent.KEYCODE_BUTTON_L2 to Hotkey.SAVE_STATE,
        KeyEvent.KEYCODE_BUTTON_THUMBL to Hotkey.LOAD_STATE,
    )

    /** Atalhos no teclado (PC, Chromebook, tablet com teclado). */
    private val keyboardHotkeys = mapOf(
        KeyEvent.KEYCODE_TAB to Hotkey.FAST_FORWARD,
        KeyEvent.KEYCODE_F1 to Hotkey.SAVE_STATE,
        KeyEvent.KEYCODE_F4 to Hotkey.LOAD_STATE,
    )

    /**
     * Teclado e controle remoto da TV -> botões do controle.
     *   Setas = direcional | X = A | Z = B | S = X | A = Y
     *   Q = L | W = R | 1 = L2 | 2 = R2 | Enter = START | Backspace/Shift = SELECT
     *   OK/centro do controle remoto da TV = A
     */
    private val keyboardToGamepad = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER to KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_X to KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_Z to KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_S to KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_A to KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_Q to KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_W to KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_1 to KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_2 to KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_ENTER to KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_DEL to KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_SHIFT_RIGHT to KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    fun isGamepad(event: KeyEvent): Boolean {
        val src = event.source
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * Consoles que usam L2/R2/L3 (N64, PlayStation) não recebem atalhos no controle,
     * para não roubar botões do jogo. Neles, use os botões da tela ou o teclado.
     */
    fun hotkeyFor(event: KeyEvent, platform: Platform): Hotkey? = when {
        !isGamepad(event) -> keyboardHotkeys[event.keyCode]
        platform.usesTriggers -> null
        else -> gamepadHotkeys[event.keyCode]
    }

    fun keyboardToGamepad(keyCode: Int): Int? = keyboardToGamepad[keyCode]

    /** Existe algum controle físico conectado (USB ou Bluetooth)? */
    fun hasPhysicalGamepad(): Boolean =
        InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            val s = dev.sources
            !dev.isVirtual &&
                ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        }
}
