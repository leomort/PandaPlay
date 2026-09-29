package com.pandaplay.emu

import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.event.Event
import com.swordfish.radialgamepad.library.haptics.HapticConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Tela do jogo. Recebe input de:
 *  - controle virtual (toque)                -> escondido quando há controle físico
 *  - controle Bluetooth/USB (Xbox, DualSense, 8BitDo...) com até 4 portas
 *  - teclado (PC/Chromebook/tablet) e controle remoto da TV
 */
class GameActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        private const val TAG = "PandaPlay"


        private const val AUTOSAVE_INTERVAL_MS = 15_000L
    }

    private lateinit var storage: GameStorage
    private lateinit var rom: File
    private lateinit var platform: Platform
    private lateinit var retroView: GLRetroView

    private lateinit var padsRow: View
    private lateinit var btnFast: TextView
    private lateinit var controls: ControlSettings
    private var padJob: Job? = null
    private var fastSpeed = 3
    /** Botões do controle Bluetooth pelo nome (A é A) em vez da posição. */
    private var faceByLabel = false

    private var fastForward = false

    /** Tempo jogado: conta só enquanto a tela do jogo está aberta e ativa. */
    private var sessionStart = 0L
    private var libPrefs: LibraryPrefs? = null
    private var lastSram: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        storage = GameStorage(this)
        rom = File(intent.getStringExtra(EXTRA_ROM_PATH) ?: run { finish(); return })

        platform = Platform.of(rom) ?: run {
            Toast.makeText(this, "Formato de jogo não suportado", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val corePath = File(applicationInfo.nativeLibraryDir, platform.coreFile)
        if (!corePath.exists()) {
            Toast.makeText(
                this,
                "O emulador de ${platform.label} não veio neste APK. Confira o passo \"Baixar cores\" no GitHub Actions.",
                Toast.LENGTH_LONG
            ).show()
            finish(); return
        }

        lastSram = storage.readSram(rom)
        libPrefs = LibraryPrefs(this).also { it.markPlayed(rom) }

        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath.absolutePath
            gameFilePath = rom.absolutePath
            systemDirectory = storage.systemDir.absolutePath
            savesDirectory = storage.savesDir.absolutePath
            saveRAMState = lastSram          // restaura o save do jogo (ex: progresso no Pokémon)
            // 2D: pixels nítidos. 3D (N64, PS1): filtro padrão, que fica melhor em polígonos.
            shader = if (platform.is3D) ShaderConfig.Default else ShaderConfig.Sharp
            variables = platform.coreOptions.map { Variable(it.first, it.second) }.toTypedArray()
            preferLowLatencyAudio = true
            rumbleEventsEnabled = true
        }

        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)
        findViewById<FrameLayout>(R.id.gameContainer).addView(
            retroView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        padsRow = findViewById(R.id.padsRow)
        controls = ControlSettings(this)
        setupHud()
        applyControlSettings()
        observeErrors()
        startAutosave()

        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        updatePadVisibility()
        applyLayout()
    }

    // ---------------------------------------------------------------- saves

    /** Salva a SRAM periodicamente enquanto o jogo roda (proteção contra fechar o app à força). */
    private fun startAutosave() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(AUTOSAVE_INTERVAL_MS)
                    persistSram(useEmulationThread = true)
                }
            }
        }
    }

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        sessionStart = System.currentTimeMillis()
        // Voltando da tela ⚙: aplica as mudanças de controle sem reiniciar o jogo
        if (!firstResume && ::retroView.isInitialized) {
            applyControlSettings()
            applyLayout()
        }
        firstResume = false
    }

    override fun onPause() {
        if (sessionStart > 0 && ::rom.isInitialized) {
            libPrefs?.addPlayTime(rom, System.currentTimeMillis() - sessionStart)
            libPrefs?.markPlayed(rom)
            sessionStart = 0
        }
        // Aqui a emulação já está pausada, então lemos a SRAM direto, sem fila da thread GL.
        if (::retroView.isInitialized) persistSram(useEmulationThread = false)
        super.onPause()
    }

    private fun persistSram(useEmulationThread: Boolean) {
        try {
            val sram = retroView.serializeSRAM(useEmulationThread)
            if (sram.isNotEmpty() && !sram.contentEquals(lastSram)) {
                storage.writeSram(rom, sram)
                lastSram = sram
                Log.i(TAG, "SRAM salva (${sram.size} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar SRAM", e)
        }
    }

    private fun saveState() {
        lifecycleScope.launch {
            val state = retroView.serializeState()
            withContext(Dispatchers.IO) { storage.stateFile(rom).writeBytes(state) }
            toast("Estado salvo")
        }
    }

    private fun loadState() {
        lifecycleScope.launch {
            val file = storage.stateFile(rom)
            if (!file.exists()) { toast("Nenhum estado salvo ainda"); return@launch }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val ok = retroView.unserializeState(bytes)
            toast(if (ok) "Estado carregado" else "Não foi possível carregar o estado")
        }
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        retroView.frameSpeed = if (fastForward) fastSpeed else 1
        btnFast.text = if (fastForward) "⏩ ${fastSpeed}x" else "⏩ 1x"
    }

    private fun runHotkey(hotkey: Hotkey) = when (hotkey) {
        Hotkey.FAST_FORWARD -> toggleFastForward()
        Hotkey.SAVE_STATE -> saveState()
        Hotkey.LOAD_STATE -> loadState()
    }

    // ---------------------------------------------------------------- UI

    private fun setupHud() {
        btnFast = findViewById(R.id.btnFast)
        btnFast.setOnClickListener { toggleFastForward() }
        findViewById<View>(R.id.btnSaveState).setOnClickListener { saveState() }
        findViewById<View>(R.id.btnLoadState).setOnClickListener { loadState() }
        findViewById<View>(R.id.btnControls).setOnClickListener {
            startActivity(
                Intent(this, ControlSettingsActivity::class.java)
                    .putExtra(ControlSettingsActivity.EXTRA_PLATFORM, platform.name)
            )
        }
    }

    /**
     * Monta o controle na tela conforme as configurações (setas ou analógico, tamanho,
     * posição, vibração). É chamado de novo ao voltar da tela ⚙, sem reiniciar o jogo.
     */
    private fun setupVirtualPad() {
        val c = controls
        val p = platform
        val dpad = c.get(ControlSettings.Setting.DPAD, p)
        val useStick = when (dpad) {
            "stick" -> true
            "cross" -> false
            else -> p.pad.stickByDefault
        }
        val haptic = if (c.get(ControlSettings.Setting.HAPTIC, p) == "on") HapticConfig.PRESS else HapticConfig.OFF
        val d = resources.displayMetrics.density
        val maxSizeDp = padSizeDp()
        val edgePx = edgeDp() * d
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Altura: cada posição do celular (deitado / em pé) tem a sua configuração
        val liftPx = if (landscape) {
            when (c.get(ControlSettings.Setting.HEIGHT, p)) {
                "mid" -> 60 * d
                "high" -> 130 * d
                else -> 0f
            }
        } else {
            when (c.get(ControlSettings.Setting.HEIGHT_PORTRAIT, p)) {
                "mid" -> 50 * d
                "high" -> 100 * d
                "top" -> 160 * d
                else -> 0f
            }
        }

        val left = RadialGamePad(p.pad.left(useStick, haptic), 8f, this).apply {
            gravityX = -1f; gravityY = 1f
            primaryDialMaxSizeDp = maxSizeDp
            offsetX = edgePx
            offsetY = -liftPx
        }
        val right = RadialGamePad(p.pad.right(haptic), 8f, this).apply {
            gravityX = 1f; gravityY = 1f
            primaryDialMaxSizeDp = maxSizeDp
            offsetX = -edgePx
            offsetY = -liftPx
        }
        findViewById<FrameLayout>(R.id.leftPad).apply { removeAllViews(); addView(left) }
        findViewById<FrameLayout>(R.id.rightPad).apply { removeAllViews(); addView(right) }

        padJob?.cancel()
        padJob = lifecycleScope.launch {
            merge(left.events(), right.events())
                .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                .collect { event ->
                    when (event) {
                        is Event.Button -> retroView.sendKeyEvent(event.action, event.id)
                        is Event.Direction -> {
                            // Analógico no lugar das setas: vira direcional digital (8 direções)
                            if (event.id == PadLayouts.DPAD) {
                                retroView.sendMotionEvent(event.id, digital(event.xAxis), digital(event.yAxis))
                            } else {
                                retroView.sendMotionEvent(event.id, event.xAxis, event.yAxis)
                            }
                        }
                        else -> Unit
                    }
                }
        }
    }

    private fun padSizeDp(): Float = when (controls.get(ControlSettings.Setting.SIZE, platform)) {
        "small" -> 95f
        "large" -> 170f
        else -> 130f
    }

    private fun edgeDp(): Float = when (controls.get(ControlSettings.Setting.EDGE, platform)) {
        "medium" -> 24f
        "far" -> 56f
        else -> 0f
    }

    private fun digital(v: Float) = when {
        v > 0.45f -> 1f
        v < -0.45f -> -1f
        else -> 0f
    }

    /** Lê as configurações de controle e aplica tudo (também ao voltar da tela ⚙). */
    private fun applyControlSettings() {
        fastSpeed = controls.get(ControlSettings.Setting.FAST, platform).toIntOrNull() ?: 3
        if (fastForward) retroView.frameSpeed = fastSpeed
        btnFast.text = if (fastForward) "⏩ ${fastSpeed}x" else "⏩ 1x"
        faceByLabel = controls.get(ControlSettings.Setting.FACE, platform) == "label"
        setupVirtualPad()
        updatePadVisibility()
    }

    /** Com controle físico conectado, o controle virtual some e o jogo ganha a tela toda. */
    private fun updatePadVisibility() {
        val show = when (controls.get(ControlSettings.Setting.SHOW, platform)) {
            "always" -> true
            "never" -> false
            else -> !InputMapper.hasPhysicalGamepad()
        }
        padsRow.visibility = if (show) View.VISIBLE else View.GONE
        applyLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::retroView.isInitialized) setupVirtualPad()
        applyLayout()
    }

    /**
     * Organiza a tela conforme a posição do aparelho, sem reiniciar o jogo:
     *  - deitado: jogo ocupa a tela toda e o controle fica por cima, semitransparente, nas laterais
     *  - em pé: jogo em cima e controle embaixo (o DS ganha mais espaço para as duas telas)
     *  - com controle físico: jogo usa a tela toda nas duas posições
     */
    private fun applyLayout() {
        val root = findViewById<View>(R.id.gameRoot) ?: return
        root.post {
            val d = resources.displayMetrics.density
            val game = findViewById<View>(R.id.gameContainer) ?: return@post
            val left = findViewById<View>(R.id.leftPad)
            val right = findViewById<View>(R.id.rightPad)
            val spacer = findViewById<View>(R.id.padSpacer)
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val padsVisible = padsRow.visibility == View.VISIBLE
            val full = FrameLayout.LayoutParams.MATCH_PARENT

            if (landscape || !padsVisible) {
                game.layoutParams = FrameLayout.LayoutParams(full, full)
                padsRow.layoutParams = FrameLayout.LayoutParams(full, full)
                padsRow.alpha = (controls.get(ControlSettings.Setting.OPACITY, platform).toIntOrNull() ?: 70) / 100f
                // largura de cada lado cresce com o tamanho do controle e a distância da borda
                val side = ((padSizeDp() + 70f + edgeDp()) * d).toInt()
                left?.layoutParams = LinearLayout.LayoutParams(side, full, 0f)
                right?.layoutParams = LinearLayout.LayoutParams(side, full, 0f)
                spacer?.layoutParams = LinearLayout.LayoutParams(0, full, 1f)
            } else {
                val height = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                val top = (56 * d).toInt() // espaço dos botões ⏩ 💾 📂
                val fraction = if (platform.touchScreen) 0.60f else 0.48f
                val gameHeight = (height * fraction).toInt()
                game.layoutParams = FrameLayout.LayoutParams(full, gameHeight).apply {
                    topMargin = top; gravity = Gravity.TOP
                }
                padsRow.layoutParams = FrameLayout.LayoutParams(full, height - gameHeight - top).apply {
                    gravity = Gravity.BOTTOM
                }
                padsRow.alpha = 1f
                left?.layoutParams = LinearLayout.LayoutParams(0, full, 1f)
                right?.layoutParams = LinearLayout.LayoutParams(0, full, 1f)
                spacer?.layoutParams = LinearLayout.LayoutParams(0, full, 0f)
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = updatePadVisibility()
    override fun onInputDeviceRemoved(deviceId: Int) = updatePadVisibility()
    override fun onInputDeviceChanged(deviceId: Int) = updatePadVisibility()

    private fun observeErrors() {
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code ->
                Log.e(TAG, "Erro no LibretroDroid: $code")
                toast("Erro ao iniciar o jogo (código $code)")
                finish()
            }
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- input físico

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Voltar/ESC sempre sai do jogo (o save é gravado no onPause)
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE
        ) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }

        InputMapper.hotkeyFor(event, platform)?.let { hotkey ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) runHotkey(hotkey)
            return true
        }

        // Controle físico: o GLRetroView já converte o layout Android -> RetroPad e escolhe a porta
        if (InputMapper.isGamepad(event) && (event.device?.controllerNumber ?: 0) > 0) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> retroView.onKeyDown(faceKey(event.keyCode), event)
                KeyEvent.ACTION_UP -> retroView.onKeyUp(faceKey(event.keyCode), event)
                else -> super.dispatchKeyEvent(event)
            }
        }

        // Teclado ou controle remoto da TV -> jogador 1
        InputMapper.keyboardToGamepad(event.keyCode)?.let { mapped ->
            if (event.repeatCount == 0) retroView.sendKeyEvent(event.action, mapped, 0)
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private var l2Down = false
    private var r2Down = false

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val port = ((event.device?.controllerNumber ?: 1) - 1).coerceAtLeast(0)

        if (platform.analog) {
            // N64 e PS1: direcional, analógico esquerdo e direito vão direto para o jogo
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), port
            )
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), port
            )
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), port
            )
            // Gatilhos analógicos (controles de Xbox, por exemplo) viram L2/R2
            val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
            val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
            if (l2 != l2Down) {
                l2Down = l2
                retroView.sendKeyEvent(if (l2) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2, port)
            }
            if (r2 != r2Down) {
                r2Down = r2
                retroView.sendKeyEvent(if (r2) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2, port)
            }
            return true
        }

        // Consoles 2D: direcional digital e analógico esquerdo movem o personagem
        var x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        var y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (abs(x) < 0.5f && abs(y) < 0.5f) {
            x = deadzone(event.getAxisValue(MotionEvent.AXIS_X))
            y = deadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        }
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, port)
        return true
    }

    /**
     * Por padrão o botão vale pela posição (o de baixo é o B, como no Super Nintendo).
     * Com "pelo nome", o botão escrito A é o A do jogo.
     */
    private fun faceKey(keyCode: Int): Int {
        if (!faceByLabel) return keyCode
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }
    }

    private fun deadzone(v: Float) = when {
        v > 0.5f -> 1f
        v < -0.5f -> -1f
        else -> 0f
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }
}
