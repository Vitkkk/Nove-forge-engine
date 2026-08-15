package dev.novaforge.engine

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import dev.novaforge.engine.core.runtime.EngineRuntime
import dev.novaforge.engine.core.storage.ProjectStorage
import dev.novaforge.engine.editor.NovaViewportView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var storage: ProjectStorage
    private lateinit var project: DocumentFile
    private lateinit var viewport: NovaViewportView
    private lateinit var debugOverlay: TextView
    private lateinit var projectName: String
    private var runtime: EngineRuntime? = null
    private var lastFrameNanos = 0L
    private var paused = false
    private var pauseMenuOpen = false
    private var baseWidth = 1920
    private var baseHeight = 1080
    private var fpsAccumulator = 0f
    private var fpsFrames = 0
    private var shownFps = 0
    private var shownFrameMs = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: return finish()
        storage = ProjectStorage(this)
        runCatching {
            project = storage.projectByName(projectName)
            val config = storage.loadConfig(project)
            baseWidth = config.width; baseHeight = config.height
            requestedOrientation = if (config.orientation == "landscape") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }.onFailure { toast("Não foi possível iniciar o jogo: ${it.message}"); finish(); return }

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()
        setContentView(buildUi())
        startRuntime()
    }

    private fun buildUi(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        viewport = NovaViewportView(context).apply {
            imageLoader = { path -> resolveProjectFile(path)?.let { file -> runCatching { contentResolver.openInputStream(file.uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }
        }
        addView(viewport, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        debugOverlay = TextView(context).apply {
            text = "NovaForge Debug"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(Color.argb(185, 10, 13, 19), 9)
            alpha = .92f
            setOnClickListener { visibility = View.GONE }
        }
        addView(debugOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = dp(10); marginStart = dp(10) })

        addView(Button(context).apply {
            text = "⋮"; textSize = 23f; setTextColor(Color.WHITE); background = rounded(Color.argb(190, 29, 34, 46), 12); setOnClickListener { showPauseMenu() }
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.END).apply { topMargin = dp(10); marginEnd = dp(10) })
    }

    private fun startRuntime() {
        runtime?.stop()
        val playScene = storage.loadMainScene(project)
        viewport.scene = playScene
        runtime = EngineRuntime(playScene,
            scriptLoader = { path -> runCatching { storage.readText(project, path) }.getOrNull() },
            blocksLoader = { path -> runCatching { storage.readText(project, path) }.getOrNull() },
            baseWidth = baseWidth, baseHeight = baseHeight
        ).also { engine ->
            engine.console.addListener { entry -> if (entry.level.name == "ERROR") runOnUiThread { toast(entry.display()) } }
            engine.start()
        }
        viewport.runtime = runtime
        lastFrameNanos = 0L; fpsAccumulator = 0f; fpsFrames = 0
        Choreographer.getInstance().removeFrameCallback(this); Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val engine = runtime ?: return
        if (!paused) {
            if (lastFrameNanos != 0L) {
                val delta = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                engine.frame(delta)
                fpsAccumulator += delta; fpsFrames++
                if (fpsAccumulator >= .5f) {
                    shownFps = (fpsFrames / fpsAccumulator).toInt()
                    shownFrameMs = if (shownFps > 0) 1000f / shownFps else 0f
                    fpsAccumulator = 0f; fpsFrames = 0
                    updateDebugOverlay(engine)
                }
            }
            lastFrameNanos = frameTimeNanos; viewport.frame()
            when { engine.requestStop -> { finish(); return }; engine.requestReload -> { startRuntime(); return } }
        } else lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun updateDebugOverlay(engine: EngineRuntime) {
        if (debugOverlay.visibility != View.VISIBLE) return
        val objects = engine.scene.root.walk().count()
        debugOverlay.text = "NovaForge 0.2  DEBUG\nFPS  $shownFps   Frame  ${"%.1f".format(Locale.US, shownFrameMs)} ms\nObjects  $objects   Base  ${baseWidth}×${baseHeight}"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = showPauseMenu()

    private fun showPauseMenu() {
        if (pauseMenuOpen || isFinishing) return
        paused = true; pauseMenuOpen = true
        val options = arrayOf("▶  Continuar", "▣  Tirar screenshot", "↻  Reiniciar jogo", "◉  Mostrar/ocultar debug", "←  Sair para o editor")
        val dialog = AlertDialog.Builder(this).setTitle("$projectName  •  Play Mode").setItems(options) { d, which ->
            when (which) {
                0 -> { paused = false; d.dismiss() }
                1 -> { captureScreenshot(); paused = false; d.dismiss() }
                2 -> { d.dismiss(); paused = false; startRuntime() }
                3 -> { debugOverlay.visibility = if (debugOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE; paused = false; d.dismiss() }
                4 -> { d.dismiss(); finish() }
            }
        }.setOnCancelListener { paused = false }.create()
        dialog.setOnDismissListener { pauseMenuOpen = false; if (!isFinishing) paused = false }
        dialog.show()
    }

    private fun captureScreenshot() {
        if (viewport.width <= 0 || viewport.height <= 0) return
        runCatching {
            val bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
            viewport.draw(Canvas(bitmap)); saveScreenshot(bitmap)
        }.onSuccess { toast("Screenshot salvo em NovaForge/Exports/Screenshots") }.onFailure { toast("Falha ao salvar screenshot: ${it.message}") }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val root = storage.ensureWorkspace()
        val exports = root.findFile("Exports")?.takeIf { it.isDirectory } ?: root.createDirectory("Exports") ?: error("Não foi possível abrir Exports")
        val screenshots = exports.findFile("Screenshots")?.takeIf { it.isDirectory } ?: exports.createDirectory("Screenshots") ?: error("Não foi possível criar Screenshots")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${projectName}_${stamp}.png"; screenshots.findFile(name)?.delete()
        val file = screenshots.createFile("image/png", name) ?: error("Não foi possível criar o PNG")
        contentResolver.openOutputStream(file.uri, "w")!!.use { output -> require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Falha ao comprimir screenshot" } }
    }

    private fun resolveProjectFile(relativePath: String): DocumentFile? {
        val segments = relativePath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        var current = project; for (segment in segments) current = current.findFile(segment) ?: return null; return current
    }

    override fun onDestroy() { Choreographer.getInstance().removeFrameCallback(this); runtime?.stop(); runtime = null; super.onDestroy() }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_PROJECT_NAME = "project_name" }
}
