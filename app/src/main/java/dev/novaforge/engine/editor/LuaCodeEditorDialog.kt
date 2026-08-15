package dev.novaforge.engine.editor

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import org.luaj.vm2.LuaError
import org.luaj.vm2.lib.jse.JsePlatform

class LuaCodeEditorDialog(
    private val context: Context,
    private val path: String,
    source: String,
    private val onSave: (String) -> Unit
) {
    private val dialog = Dialog(context)
    private val editor = LuaEditorView(context).apply { setText(source) }
    private lateinit var status: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var replace: EditText

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildUi())
        dialog.show()
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        editor.requestFocus()
    }

    private fun buildUi(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(12, 15, 22))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(19, 24, 34))
            addView(button("←", dp(52)) { dialog.dismiss() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                addView(TextView(context).apply { text = path.substringAfterLast('/'); textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(context).apply { text = path; textSize = 11f; setTextColor(Color.rgb(137, 151, 173)) })
            }, LinearLayout.LayoutParams(0, dp(54), 1f))
            addView(button("⌕", dp(52)) { searchRow.visibility = if (searchRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
            addView(button("✓", dp(52)) { validateAndSave() })
        })

        searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.rgb(16, 20, 29))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                search = EditText(context).apply { hint = "Buscar"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
                addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(button("Próx.", dp(74)) { findNext() })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                replace = EditText(context).apply { hint = "Substituir por"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
                addView(replace, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(button("Tudo", dp(74)) { replaceAll() })
            })
        }
        addView(searchRow)

        addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(context).apply {
            text = "Lua • pronto"
            textSize = 11f
            setTextColor(Color.rgb(150, 166, 188))
            setBackgroundColor(Color.rgb(19, 24, 34))
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        addView(status)
        addView(snippetBar())
        addView(symbolBar())
    }

    private fun snippetBar(): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.rgb(15, 19, 27))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                "Eventos" to { showSnippets(EVENT_SNIPPETS) },
                "NovaForge API" to { showSnippets(API_SNIPPETS) },
                "Node" to { showSnippets(NODE_SNIPPETS) },
                "Controle" to { showSnippets(CONTROL_SNIPPETS) }
            ).forEach { (label, action) -> addView(button(label) { action() }) }
        })
    }

    private fun symbolBar(): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.rgb(10, 13, 19))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("(", ")", "[", "]", "{", "}", "=", "+", "-", "*", "/", ".", ",", "\"", "'", ":", "_", "<", ">", "~").forEach { symbol ->
                addView(button(symbol, dp(46)) { insert(symbol) })
            }
        })
    }

    private fun validateAndSave() {
        val code = editor.text?.toString().orEmpty()
        try {
            JsePlatform.standardGlobals().load(code, path)
            status.text = "Lua • sintaxe válida • salvo"
            status.setTextColor(Color.rgb(98, 214, 146))
            onSave(code)
            Toast.makeText(context, "Script salvo", Toast.LENGTH_SHORT).show()
        } catch (e: LuaError) {
            status.text = "Erro Lua: ${e.message?.lineSequence()?.firstOrNull() ?: "sintaxe inválida"}"
            status.setTextColor(Color.rgb(255, 116, 122))
            AlertDialog.Builder(context).setTitle("Erro de sintaxe Lua").setMessage(e.message ?: "Não foi possível validar o script.")
                .setPositiveButton("Voltar ao código", null).show()
        }
    }

    private fun findNext() {
        val term = search.text.toString()
        if (term.isBlank()) return
        val text = editor.text?.toString().orEmpty()
        val start = editor.selectionEnd.coerceAtLeast(0)
        var index = text.indexOf(term, start, ignoreCase = true)
        if (index < 0) index = text.indexOf(term, 0, ignoreCase = true)
        if (index >= 0) {
            editor.requestFocus(); editor.setSelection(index, index + term.length)
            status.text = "Encontrado na linha ${text.take(index).count { it == '\n' } + 1}"
        } else status.text = "Nenhuma ocorrência de '$term'"
    }

    private fun replaceAll() {
        val from = search.text.toString()
        if (from.isBlank()) return
        val before = editor.text?.toString().orEmpty()
        val after = before.replace(from, replace.text.toString(), ignoreCase = false)
        editor.setText(after); editor.setSelection(editor.text?.length ?: 0)
        status.text = "${countOccurrences(before, from)} ocorrência(s) substituída(s)"
    }

    private fun countOccurrences(text: String, term: String): Int {
        var count = 0; var at = 0
        while (true) { val i = text.indexOf(term, at); if (i < 0) return count; count++; at = i + term.length }
    }

    private fun showSnippets(items: List<Pair<String, String>>) {
        AlertDialog.Builder(context).setTitle("Inserir código").setItems(items.map { it.first }.toTypedArray()) { _, which -> insert(items[which].second) }.show()
    }

    private fun insert(value: String) {
        val editable = editor.text ?: return
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        editable.replace(start, end, value)
        editor.setSelection((start + value.length).coerceAtMost(editable.length))
    }

    private fun button(label: String, width: Int? = null, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        minWidth = width ?: dp(82); minimumHeight = dp(44); setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private val EVENT_SNIPPETS = listOf(
            "ready()" to "function ready()\n    \nend\n",
            "update(delta)" to "function update(delta)\n    \nend\n",
            "fixedUpdate(delta)" to "function fixedUpdate(delta)\n    \nend\n",
            "input(event)" to "function input(event)\n    \nend\n",
            "onTouch(event)" to "function onTouch(event)\n    \nend\n"
        )
        private val API_SNIPPETS = listOf(
            "Buscar node" to "local node = NovaForge.getNode(\"Player\")",
            "Buscar por tag" to "local enemies = NovaForge.findByTag(\"enemy\")",
            "Emitir sinal" to "NovaForge.emitSignal(\"signal_name\")",
            "Receber sinal" to "NovaForge.onSignal(\"signal_name\", function(value)\n    \nend)",
            "Console" to "NovaForge.print(\"mensagem\")",
            "Recarregar cena" to "NovaForge.reloadScene()"
        )
        private val NODE_SNIPPETS = listOf(
            "Mover" to "self:move(10, 0)",
            "Posição" to "self:setPosition(100, 100)",
            "Rotacionar" to "self:rotate(5)",
            "X/Y" to "self.x = self.x + 100 * delta\nself.y = self.y",
            "Visibilidade" to "self.visible = true"
        )
        private val CONTROL_SNIPPETS = listOf(
            "if" to "if condition then\n    \nend",
            "if / else" to "if condition then\n    \nelse\n    \nend",
            "for" to "for i = 1, 10 do\n    \nend",
            "while" to "while condition do\n    \nend",
            "função" to "local function myFunction(value)\n    return value\nend"
        )
    }
}

private class LuaEditorView(context: Context) : AppCompatEditText(context) {
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(92, 106, 128); textSize = 12f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.RIGHT }
    private val dividerPaint = Paint().apply { color = Color.rgb(38, 45, 59); strokeWidth = resources.displayMetrics.density }
    private var highlighting = false
    private val gutter = (54 * resources.displayMetrics.density).toInt()

    init {
        setBackgroundColor(Color.rgb(12, 15, 22)); setTextColor(Color.rgb(221, 228, 239)); textSize = 14f
        typeface = Typeface.MONOSPACE; gravity = Gravity.TOP or Gravity.START; setHorizontallyScrolling(true)
        setPadding(gutter + (10 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt(), 12, 12)
        includeFontPadding = false
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!highlighting) post { highlight() } }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        post { highlight() }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, gutter.toFloat(), height.toFloat(), Paint().apply { color = Color.rgb(9, 12, 18) })
        canvas.drawLine(gutter.toFloat(), 0f, gutter.toFloat(), height.toFloat(), dividerPaint)
        val l = layout
        if (l != null) {
            for (line in 0 until l.lineCount) {
                val baseline = l.getLineBaseline(line).toFloat() + totalPaddingTop - scrollY
                if (baseline > -lineHeight && baseline < height + lineHeight) canvas.drawText((line + 1).toString(), gutter - 9f * resources.displayMetrics.density, baseline, gutterPaint)
            }
        }
        super.onDraw(canvas)
    }

    private fun highlight() {
        val editable = text ?: return
        highlighting = true
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach(editable::removeSpan)
        val source = editable.toString()
        applyRegex(editable, source, Regex("--\\[\\[[\\s\\S]*?]]|--[^\\n]*"), Color.rgb(104, 137, 105))
        applyRegex(editable, source, Regex("\\b(function|end|local|if|then|else|elseif|for|while|do|repeat|until|return|break|and|or|not|in|nil|true|false)\\b"), Color.rgb(214, 132, 255))
        applyRegex(editable, source, Regex("\\b(NovaForge|self|touch|screen|math|string|table)\\b"), Color.rgb(85, 185, 255))
        applyRegex(editable, source, Regex("(?<![\\w.])[-+]?\\d+(?:\\.\\d+)?"), Color.rgb(246, 192, 104))
        applyRegex(editable, source, Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), Color.rgb(139, 210, 143))
        applyRegex(editable, source, Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?=\\()"), Color.rgb(112, 214, 226))
        highlighting = false
        invalidate()
    }

    private fun applyRegex(editable: Editable, source: String, regex: Regex, color: Int) {
        regex.findAll(source).forEach { match -> editable.setSpan(ForegroundColorSpan(color), match.range.first, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
