package dev.novaforge.engine

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.novaforge.engine.core.model.ProjectConfig
import dev.novaforge.engine.core.storage.ProjectStorage
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var storage: ProjectStorage
    private lateinit var projectContainer: LinearLayout
    private lateinit var workspaceLabel: TextView
    private lateinit var emptyState: TextView

    private val workspacePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { storage.setWorkspace(uri) }
            .onSuccess { refresh() }
            .onFailure { toast(it.message ?: "Não foi possível usar essa pasta") }
    }

    private val projectImporter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { storage.importProjectZip(uri) }
            .onSuccess { refresh(); toast("Projeto importado") }
            .onFailure { toast("Falha ao importar: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = ProjectStorage(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::projectContainer.isInitialized) refresh()
    }

    private fun buildUi(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundColor(SURFACE)
            addView(TextView(context).apply {
                text = "N"
                gravity = Gravity.CENTER
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(ACCENT, 14f)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(context).apply {
                    text = "NovaForge Engine"
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(TEXT)
                })
                addView(TextView(context).apply {
                    text = "Mobile game development workspace"
                    textSize = 12f
                    setTextColor(MUTED)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "0.2"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ACCENT_LIGHT)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(Color.rgb(39, 31, 72), 20f)
            })
        })

        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(30))

                addView(TextView(context).apply {
                    text = "Crie. Programe. Teste."
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(TEXT)
                })
                addView(TextView(context).apply {
                    text = "Uma engine 2D completa pensada para desenvolver diretamente no Android."
                    textSize = 14f
                    setTextColor(MUTED)
                    setPadding(0, dp(5), 0, dp(18))
                })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(SURFACE_2, 14f)
                    addView(TextView(context).apply {
                        text = "WORKSPACE"
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(ACCENT_LIGHT)
                    })
                    workspaceLabel = TextView(context).apply {
                        textSize = 12f
                        setTextColor(MUTED)
                        setPadding(0, dp(5), 0, dp(8))
                    }
                    addView(workspaceLabel)
                    addView(secondaryButton("Alterar pasta NovaForge") { workspacePicker.launch(storage.workspaceUri) })
                })

                addView(TextView(context).apply {
                    text = "Ações rápidas"
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(TEXT)
                    setPadding(0, dp(22), 0, dp(9))
                })
                addView(primaryButton("＋  Novo projeto") { createProjectDialog() })
                addView(secondaryButton("⇩  Importar projeto ZIP") {
                    if (requireWorkspace()) projectImporter.launch(arrayOf("application/zip", "application/octet-stream"))
                }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) } })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(24), 0, dp(10))
                    addView(TextView(context).apply {
                        text = "Projetos"
                        textSize = 19f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(TEXT)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(context).apply {
                        text = "Toque para abrir"
                        textSize = 11f
                        setTextColor(MUTED)
                    })
                })

                projectContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(projectContainer)
                emptyState = TextView(context).apply {
                    text = "Nenhum projeto ainda.\nCrie seu primeiro jogo com o botão acima."
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(MUTED)
                    setPadding(dp(20), dp(42), dp(20), dp(42))
                    background = rounded(SURFACE_2, 16f)
                }
                addView(emptyState)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun refresh() {
        workspaceLabel.text = storage.workspaceUri?.let { "Pasta autorizada e persistida pelo Android" }
            ?: "Nenhuma pasta autorizada. Escolha NovaForge no armazenamento compartilhado."
        val projects = runCatching { storage.listProjects() }.getOrDefault(emptyList())
        projectContainer.removeAllViews()
        emptyState.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        projects.forEach { info ->
            val modified = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(info.modifiedAt))
            projectContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(SURFACE_2, 15f)
                isClickable = true; isFocusable = true
                setOnClickListener { openProject(info.name) }
                addView(TextView(context).apply {
                    text = info.name.take(1).uppercase()
                    gravity = Gravity.CENTER
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = rounded(ACCENT, 12f)
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                    addView(TextView(context).apply { text = info.name; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TEXT) })
                    addView(TextView(context).apply { text = "NovaForge ${info.engineVersion}  •  $modified"; textSize = 11f; setTextColor(MUTED) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply { text = "›"; textSize = 28f; setTextColor(ACCENT_LIGHT) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
        }
    }

    private fun createProjectDialog() {
        if (!requireWorkspace()) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        val name = styledField("Nome do projeto", "MyGame")
        val width = styledField("Largura base", "1920").apply { inputType = 2 }
        val height = styledField("Altura base", "1080").apply { inputType = 2 }
        form.addView(name); form.addView(width); form.addView(height)
        AlertDialog.Builder(this)
            .setTitle("Novo projeto")
            .setMessage("Configure a resolução base. Você poderá alterar isso depois nas configurações do projeto.")
            .setView(form)
            .setPositiveButton("Criar projeto") { _, _ ->
                runCatching {
                    val w = width.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1920
                    val h = height.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1080
                    val config = ProjectConfig(name = name.text.toString().trim().ifBlank { "MyGame" }, width = w, height = h, orientation = if (w >= h) "landscape" else "portrait")
                    val project = storage.createProject(config)
                    storage.loadConfig(project).name
                }.onSuccess { refresh(); openProject(it) }.onFailure { toast(it.message ?: "Falha ao criar projeto") }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun styledField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(TEXT); setHintTextColor(MUTED); setSingleLine(); setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun openProject(name: String) = startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROJECT_NAME, name))

    private fun requireWorkspace(): Boolean {
        if (storage.workspaceUri != null) return true
        workspacePicker.launch(null); return false
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = rounded(ACCENT, 14f)
        setOnClickListener { action() }; minimumHeight = dp(54)
    }

    private fun secondaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(TEXT); background = rounded(Color.rgb(38, 43, 57), 12f)
        setOnClickListener { action() }; minimumHeight = dp(46)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(10, 12, 18)
        private val SURFACE = Color.rgb(16, 19, 27)
        private val SURFACE_2 = Color.rgb(21, 25, 35)
        private val TEXT = Color.rgb(238, 241, 247)
        private val MUTED = Color.rgb(145, 156, 177)
        private val ACCENT = Color.rgb(112, 74, 235)
        private val ACCENT_LIGHT = Color.rgb(177, 158, 255)
    }
}
