package dev.novaforge.engine.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import dev.novaforge.engine.core.model.SceneDocument
import dev.novaforge.engine.core.model.SceneNode
import dev.novaforge.engine.core.runtime.EngineRuntime
import kotlin.math.abs
import kotlin.math.round

class NovaViewportView(context: Context) : View(context) {
    var scene: SceneDocument? = null
        set(value) { field = value; selectedNode = null; invalidate() }
    var runtime: EngineRuntime? = null
    var selectedNode: SceneNode? = null
        private set
    var onSelectionChanged: ((SceneNode?) -> Unit)? = null
    var onNodeDragFinished: ((SceneNode, Float, Float, Float, Float) -> Unit)? = null
    var imageLoader: ((String) -> Bitmap?)? = null

    private val gridPaint = Paint().apply { color = Color.rgb(42, 46, 58); strokeWidth = 1f }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }
    private val cameraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val bitmapCache = linkedMapOf<String, Bitmap?>()

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragStartNodeX = 0f
    private var dragStartNodeY = 0f
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private var draggingNode = false
    private var panning = false
    private var movedBeyondSlop = false
    private var snapEnabled = true
    private val grid = 32f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val beforeX = screenToWorldX(detector.focusX)
            val beforeY = screenToWorldY(detector.focusY)
            zoom = (zoom * detector.scaleFactor).coerceIn(0.15f, 8f)
            panX = detector.focusX - beforeX * zoom
            panY = detector.focusY - beforeY * zoom
            invalidate()
            return true
        }
    })

    init { setBackgroundColor(Color.rgb(18, 20, 27)) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        val activeScene = runtime?.scene ?: scene ?: return
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom)
        activeScene.root.walk()
            .filter { it.enabled && it.transform.visible && it.type != "Node" }
            .sortedBy { it.transform.zIndex }
            .forEach { drawNode(canvas, it, it === selectedNode && runtime == null) }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = grid * zoom
        if (spacing < 8f) return
        var x = ((panX % spacing) + spacing) % spacing
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += spacing }
        var y = ((panY % spacing) + spacing) % spacing
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += spacing }
    }

    private fun drawNode(canvas: Canvas, node: SceneNode, selected: Boolean) {
        val t = node.transform
        canvas.save()
        canvas.translate(t.x, t.y)
        canvas.rotate(t.rotation)
        canvas.scale(t.scaleX, t.scaleY)
        val rect = RectF(-node.width / 2f, -node.height / 2f, node.width / 2f, node.height / 2f)
        if (node.type == "Camera2D") {
            canvas.drawRect(RectF(-480f, -270f, 480f, 270f), cameraPaint)
            canvas.drawText("Camera2D", 12f, 32f, textPaint)
        } else {
            val bitmap = node.assetPath?.let { path ->
                if (!bitmapCache.containsKey(path)) bitmapCache[path] = imageLoader?.invoke(path)
                bitmapCache[path]
            }
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, rect, nodePaint)
            } else {
                nodePaint.color = node.color
                nodePaint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, 12f, 12f, nodePaint)
                val label = node.text ?: node.name
                canvas.drawText(label, rect.left + 12f, rect.centerY() + 10f, textPaint)
            }
            if (selected) canvas.drawRect(rect, selectionPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        runtime?.let { running ->
            val worldX = screenToWorldX(event.x)
            val worldY = screenToWorldY(event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> running.touch(worldX, worldY, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> running.touch(worldX, worldY, false)
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                movedBeyondSlop = false
                val worldX = screenToWorldX(event.x)
                val worldY = screenToWorldY(event.y)
                selectedNode = hitTest(worldX, worldY)
                onSelectionChanged?.invoke(selectedNode)
                draggingNode = selectedNode != null
                panning = selectedNode == null
                selectedNode?.let {
                    dragStartNodeX = it.transform.x
                    dragStartNodeY = it.transform.y
                    grabOffsetX = worldX - it.transform.x
                    grabOffsetY = worldY - it.transform.y
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                draggingNode = false
                panning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    lastX = event.x; lastY = event.y
                    return true
                }
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                if (!movedBeyondSlop && totalDx * totalDx + totalDy * totalDy >= touchSlop * touchSlop) movedBeyondSlop = true

                if (event.pointerCount > 1) {
                    panning = true
                    draggingNode = false
                }

                if (panning) {
                    panX += event.x - lastX
                    panY += event.y - lastY
                } else if (draggingNode && movedBeyondSlop) {
                    selectedNode?.let { node ->
                        var nx = screenToWorldX(event.x) - grabOffsetX
                        var ny = screenToWorldY(event.y) - grabOffsetY
                        if (snapEnabled) {
                            nx = round(nx / grid) * grid
                            ny = round(ny / grid) * grid
                        }
                        node.transform.x = nx
                        node.transform.y = ny
                    }
                }
                lastX = event.x; lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedNode?.takeIf { draggingNode && movedBeyondSlop }?.let { node ->
                    if (node.transform.x != dragStartNodeX || node.transform.y != dragStartNodeY) {
                        onNodeDragFinished?.invoke(node, dragStartNodeX, dragStartNodeY, node.transform.x, node.transform.y)
                    }
                }
                draggingNode = false
                panning = false
                movedBeyondSlop = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun screenToWorldX(screenX: Float) = (screenX - panX) / zoom
    private fun screenToWorldY(screenY: Float) = (screenY - panY) / zoom

    private fun hitTest(x: Float, y: Float): SceneNode? {
        val active = scene ?: return null
        return active.root.walk().filter { it.type != "Node" && it.type != "Camera2D" && it.enabled && it.transform.visible }
            .sortedByDescending { it.transform.zIndex }
            .firstOrNull { node ->
                val t = node.transform
                val halfW = node.width * abs(t.scaleX) / 2f
                val halfH = node.height * abs(t.scaleY) / 2f
                x in (t.x - halfW)..(t.x + halfW) && y in (t.y - halfH)..(t.y + halfH)
            }
    }

    fun selectNode(node: SceneNode?) { selectedNode = node; onSelectionChanged?.invoke(node); invalidate() }
    fun frame() = invalidate()
    fun resetView() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    fun toggleSnap() { snapEnabled = !snapEnabled }
    fun clearImageCache() { bitmapCache.values.filterNotNull().forEach { if (!it.isRecycled) it.recycle() }; bitmapCache.clear(); invalidate() }
}
