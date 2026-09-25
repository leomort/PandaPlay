package com.pandaplay.emu

import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
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
        private const val FAST_SPEED = 3
    }

    private lateinit var storage: GameStorage
    private lateinit var rom: File
    private lateinit var platform: Platform
    private lateinit var retroView: GLRetroView

    private lateinit var padsRow: View
    private lateinit var btnFast: TextView

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
        setupVirtualPad()
        setupHud()
        observeErrors()
        startAutosave()

        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        updatePadVisibility()
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

    override fun onResume() {
        super.onResume()
        sessionStart = System.currentTimeMillis()
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
        retroView.frameSpeed = if (fastForward) FAST_SPEED else 1
        btnFast.text = if (fastForward) "⏩ ${FAST_SPEED}x" else "⏩ 1x"
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
    }

    private fun setupVirtualPad() {
        // Nintendo DS em pé: as duas telas precisam de mais espaço que o controle
        if (platform.touchScreen && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            val container = findViewById<View>(R.id.gameContainer)
            (container.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.weight = 1.7f
                container.layoutParams = it
            }
        }

        val left = RadialGamePad(platform.pad.left, 8f, this).apply {
            gravityX = -1f; gravityY = 1f
        }
        val right = RadialGamePad(platform.pad.right, 8f, this).apply {
            gravityX = 1f; gravityY = 1f
        }
        findViewById<FrameLayout>(R.id.leftPad).addView(left)
        findViewById<FrameLayout>(R.id.rightPad).addView(right)

        lifecycleScope.launch {
            merge(left.events(), right.events())
                .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                .collect { event ->
                    when (event) {
                        is Event.Button -> retroView.sendKeyEvent(event.action, event.id)
                        is Event.Direction -> retroView.sendMotionEvent(event.id, event.xAxis, event.yAxis)
                        else -> Unit
                    }
                }
        }
    }

    /** Com controle físico conectado, o controle virtual some e o jogo ganha a tela toda. */
    private fun updatePadVisibility() {
        padsRow.visibility = if (InputMapper.hasPhysicalGamepad()) View.GONE else View.VISIBLE
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
                KeyEvent.ACTION_DOWN -> retroView.onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> retroView.onKeyUp(event.keyCode, event)
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
