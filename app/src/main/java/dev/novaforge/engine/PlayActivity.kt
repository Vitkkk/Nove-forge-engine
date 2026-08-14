package dev.novaforge.engine

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var projectName: String
    private var runtime: EngineRuntime? = null
    private var lastFrameNanos = 0L
    private var paused = false
    private var pauseMenuOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: return finish()
        storage = ProjectStorage(this)
        runCatching {
            project = storage.projectByName(projectName)
            val config = storage.loadConfig(project)
            requestedOrientation = if (config.orientation == "landscape") ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }.onFailure {
            toast("Não foi possível iniciar o jogo: ${it.message}")
            finish()
            return
        }

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()
        setContentView(buildUi())
        startRuntime()
    }

    private fun buildUi(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        viewport = NovaViewportView(context).apply {
            imageLoader = { path ->
                resolveProjectFile(path)?.let { file ->
                    runCatching { contentResolver.openInputStream(file.uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
                }
            }
        }
        addView(viewport, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(Button(context).apply {
            text = "⋮"
            textSize = 24f
            alpha = 0.72f
            setOnClickListener { showPauseMenu() }
        }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(10)
            marginEnd = dp(10)
        })
    }

    private fun startRuntime() {
        runtime?.stop()
        val playScene = storage.loadMainScene(project)
        viewport.scene = playScene
        runtime = EngineRuntime(
            scene = playScene,
            scriptLoader = { path -> runCatching { storage.readText(project, path) }.getOrNull() },
            blocksLoader = { path -> runCatching { storage.readText(project, path) }.getOrNull() }
        ).also { engine ->
            engine.console.addListener { entry ->
                if (entry.level.name == "ERROR") runOnUiThread { toast(entry.display()) }
            }
            engine.start()
        }
        viewport.runtime = runtime
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val engine = runtime ?: return
        if (!paused) {
            if (lastFrameNanos != 0L) engine.frame((frameTimeNanos - lastFrameNanos) / 1_000_000_000f)
            lastFrameNanos = frameTimeNanos
            viewport.frame()
            when {
                engine.requestStop -> { finish(); return }
                engine.requestReload -> { startRuntime(); return }
            }
        } else {
            lastFrameNanos = frameTimeNanos
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showPauseMenu()
    }

    private fun showPauseMenu() {
        if (pauseMenuOpen || isFinishing) return
        paused = true
        pauseMenuOpen = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Jogo pausado")
            .setItems(arrayOf("Continuar", "Tirar print", "Reiniciar jogo", "Sair para o editor")) { d, which ->
                when (which) {
                    0 -> { paused = false; d.dismiss() }
                    1 -> {
                        captureScreenshot()
                        paused = false
                        d.dismiss()
                    }
                    2 -> {
                        d.dismiss()
                        paused = false
                        startRuntime()
                    }
                    3 -> {
                        d.dismiss()
                        finish()
                    }
                }
            }
            .setOnCancelListener { paused = false }
            .create()
        dialog.setOnDismissListener {
            pauseMenuOpen = false
            if (!isFinishing) paused = false
        }
        dialog.show()
    }

    private fun captureScreenshot() {
        if (viewport.width <= 0 || viewport.height <= 0) return
        runCatching {
            val bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
            viewport.draw(Canvas(bitmap))
            saveScreenshot(bitmap)
        }.onSuccess { toast("Print salvo em NovaForge/Exports/Screenshots") }
            .onFailure { toast("Falha ao salvar print: ${it.message}") }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val root = storage.ensureWorkspace()
        val exports = root.findFile("Exports")?.takeIf { it.isDirectory } ?: root.createDirectory("Exports") ?: error("Não foi possível abrir Exports")
        val screenshots = exports.findFile("Screenshots")?.takeIf { it.isDirectory } ?: exports.createDirectory("Screenshots") ?: error("Não foi possível criar Screenshots")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${projectName}_${stamp}.png"
        screenshots.findFile(name)?.delete()
        val file = screenshots.createFile("image/png", name) ?: error("Não foi possível criar o PNG")
        contentResolver.openOutputStream(file.uri, "w")!!.use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Falha ao comprimir screenshot" }
        }
    }

    private fun resolveProjectFile(relativePath: String): DocumentFile? {
        val segments = relativePath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        var current = project
        for (segment in segments) current = current.findFile(segment) ?: return null
        return current
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this)
        runtime?.stop()
        runtime = null
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROJECT_NAME = "project_name"
    }
}
