package dev.novaforge.engine

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
    private lateinit var projectList: ListView
    private lateinit var workspaceLabel: TextView

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
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::projectList.isInitialized) refresh()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(Color.rgb(11, 13, 18))

        addView(TextView(context).apply {
            text = "NovaForge Engine"
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        addView(TextView(context).apply {
            text = "Crie jogos 2D diretamente no Android"
            textSize = 14f
            setTextColor(Color.LTGRAY)
        })

        workspaceLabel = TextView(context).apply {
            setPadding(0, dp(12), 0, dp(8))
            setTextColor(Color.rgb(180, 185, 200))
        }
        addView(workspaceLabel)

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(actionButton("Novo Projeto") { createProjectDialog() })
            addView(actionButton("Importar ZIP") {
                if (requireWorkspace()) projectImporter.launch(arrayOf("application/zip", "application/octet-stream"))
            })
            addView(actionButton("Pasta NovaForge") { workspacePicker.launch(storage.workspaceUri) })
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(TextView(context).apply {
            text = "Projetos"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(20), 0, dp(8))
        })

        projectList = ListView(context).apply {
            dividerHeight = 1
            setBackgroundColor(Color.rgb(18, 20, 27))
        }
        addView(projectList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun refresh() {
        workspaceLabel.text = storage.workspaceUri?.let { "Workspace autorizado: $it" }
            ?: "Escolha/crie a pasta NovaForge uma única vez. A permissão será persistida pelo Android."
        val projects = runCatching { storage.listProjects() }.getOrDefault(emptyList())
        val labels = projects.map {
            val modified = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.modifiedAt))
            "${it.name}\nNovaForge ${it.engineVersion}  •  $modified"
        }
        projectList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        projectList.setOnItemClickListener { _, _, position, _ -> openProject(projects[position].name) }
    }

    private fun createProjectDialog() {
        if (!requireWorkspace()) return
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val name = EditText(this).apply { hint = "Nome"; setText("MyGame") }
        val width = EditText(this).apply { hint = "Largura base"; inputType = 2; setText("1920") }
        val height = EditText(this).apply { hint = "Altura base"; inputType = 2; setText("1080") }
        form.addView(name); form.addView(width); form.addView(height)
        AlertDialog.Builder(this)
            .setTitle("Novo Projeto")
            .setView(form)
            .setPositiveButton("Criar") { _, _ ->
                runCatching {
                    val config = ProjectConfig(
                        name = name.text.toString(),
                        width = width.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1920,
                        height = height.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1080,
                        orientation = if ((width.text.toString().toIntOrNull() ?: 1920) >= (height.text.toString().toIntOrNull() ?: 1080)) "landscape" else "portrait"
                    )
                    val project = storage.createProject(config)
                    storage.loadConfig(project).name
                }.onSuccess { refresh(); openProject(it) }
                    .onFailure { toast(it.message ?: "Falha ao criar projeto") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openProject(name: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROJECT_NAME, name))
    }

    private fun requireWorkspace(): Boolean {
        if (storage.workspaceUri != null) return true
        workspacePicker.launch(null)
        return false
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
