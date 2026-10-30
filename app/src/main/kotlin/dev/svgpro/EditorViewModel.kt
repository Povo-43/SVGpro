package dev.svgpro

import android.graphics.Paint
import android.net.Uri
import android.graphics.Typeface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dev.svgpro.core.svg.SvgLoader
import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.model.SvgDocument
import dev.svgpro.core.path.PathData
import dev.svgpro.core.path.Seg
import dev.svgpro.core.svg.Element
import dev.svgpro.editor.Editor
import dev.svgpro.editor.TextMetrics

/**
 * 編集セッションの状態保持（回転・プロセス維持でドキュメントを失わない）。
 * さらに、自動バックアップ（クラッシュ復元用）を定期保存する。
 */
class EditorViewModel : ViewModel() {

    val editor = Editor()

    var currentUri: Uri? = null

    /** 復元可能なバックアップの有無（起動時に確認） */
    var hasBackup: Boolean = false
        private set

    private var backupJob: kotlinx.coroutines.Job? = null

    init {
        // テキスト測定（ヒットテスト/選択枠用）
        TextMetrics.provider = { el ->
            measureText(el)
        }
        editor.textToPathProvider = { el ->
            textToPathData(el)
        }
    }

    /** 自動バックアップ開始（30秒間隔・未保存の編集がある場合のみ） */
    fun startAutosave(context: android.content.Context) {
        if (backupJob != null) return
        val dir = java.io.File(context.cacheDir, "backup").apply { mkdirs() }
        val file = java.io.File(dir, "autosave.svg")
        hasBackup = file.exists() && file.length() > 0
        backupJob = viewModelScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(30_000)
                try {
                    if (editor.dirty) {
                        file.writeText(editor.doc.serialize())
                        hasBackup = true
                    }
                } catch (e: Exception) {
                    // バックアップ失敗は編集に影響させない
                }
            }
        }
    }

    /** クラッシュ後の復元 */
    fun restoreBackup(context: android.content.Context): Boolean {
        val file = java.io.File(context.cacheDir, "backup/autosave.svg")
        if (!file.exists()) return false
        return try {
            val text = file.readText()
            val doc = dev.svgpro.core.svg.SvgLoader.load({ tokenSourceOf(text) }, text)
            if (doc != null) {
                editor.loadDocument(doc, "restored.svg")
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun tokenSourceOf(xml: String): dev.svgpro.core.svg.TokenSource {
        val queue = ArrayDeque(dev.svgpro.AndroidTokenSource.tokenQueue(xml))
        return object : dev.svgpro.core.svg.TokenSource {
            override fun next(): dev.svgpro.core.svg.Token =
                queue.removeFirstOrNull() ?: dev.svgpro.core.svg.Token.EndDocument
        }
    }

    /** バックアップを破棄（保存完了時など） */
    fun clearBackup(context: android.content.Context) {
        try {
            java.io.File(context.cacheDir, "backup/autosave.svg").delete()
            hasBackup = false
        } catch (e: Exception) {
        }
    }

    private fun measureText(el: Element): Bounds? {
        val content = el.textContent()
        if (content.isEmpty()) return null
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.textSize = (dev.svgpro.core.util.Numbers.parse(dev.svgpro.core.model.StyleUtil.getProperty(el, "font-size")) ?: 16.0).toFloat()
        val x = dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0
        val y = dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0
        val w = paint.measureText(content).toDouble()
        val ascent = -paint.ascent().toDouble()
        val descent = paint.descent().toDouble()
        val anchor = dev.svgpro.core.model.StyleUtil.getProperty(el, "text-anchor")
        val x0 = when (anchor) {
            "middle" -> x - w / 2
            "end" -> x - w
            else -> x
        }
        return Bounds(x0, y - ascent, w, ascent + descent)
    }

    /**
     * text要素をアウトライン化（PathMeasureのサンプリングによる近似変換）。
     */
    private fun textToPathData(el: Element): PathData? {
        val content = el.textContent()
        if (content.isEmpty()) return null
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.textSize = (dev.svgpro.core.util.Numbers.parse(dev.svgpro.core.model.StyleUtil.getProperty(el, "font-size")) ?: 16.0).toFloat()
        paint.typeface = resolveTypeface(el)
        val x = dev.svgpro.core.util.Numbers.parse(el.attr("x")) ?: 0.0
        val y = dev.svgpro.core.util.Numbers.parse(el.attr("y")) ?: 0.0
        val ls = dev.svgpro.core.util.Numbers.parse(dev.svgpro.core.model.StyleUtil.getProperty(el, "letter-spacing")) ?: 0.0
        val path = android.graphics.Path()
        var cx = x.toFloat()
        for (ch in content) {
            val charPath = android.graphics.Path()
            paint.getTextPath(ch.toString(), 0, 1, cx, y.toFloat(), charPath)
            path.addPath(charPath)
            cx += paint.measureText(ch.toString()) + ls.toFloat()
        }
        return outlineToPathData(path)
    }

    private fun resolveTypeface(el: Element): Typeface {
        val family = dev.svgpro.core.model.StyleUtil.getProperty(el, "font-family") ?: "sans-serif"
        val weight = dev.svgpro.core.model.StyleUtil.getProperty(el, "font-weight") ?: "normal"
        val style = dev.svgpro.core.model.StyleUtil.getProperty(el, "font-style") ?: "normal"
        val base = when {
            family.contains("mono", true) -> Typeface.MONOSPACE
            family.contains("serif", true) && !family.contains("sans", true) -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
        val bold = weight == "bold" || weight.toIntOrNull() in 600..900
        val italic = style == "italic" || style == "oblique"
        return Typeface.create(base, (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
    }

    /** android.Path（輪郭）→ PathData（高精度サンプリング近似） */
    private fun outlineToPathData(src: android.graphics.Path): PathData? {
        val segs = ArrayList<Seg>()
        val measure = android.graphics.PathMeasure(src, false)
        var contour = 0
        while (true) {
            val len = measure.length
            if (len > 0.01f) {
                val steps = (len / 0.7f).toInt().coerceIn(8, 400)
                var first = true
                for (i in 0..steps) {
                    val pos = FloatArray(2)
                    measure.getPosTan(len * i / steps, pos, null)
                    if (first) {
                        segs.add(Seg.Move(pos[0].toDouble(), pos[1].toDouble()))
                        first = false
                    } else {
                        segs.add(Seg.Line(pos[0].toDouble(), pos[1].toDouble()))
                    }
                }
                segs.add(Seg.Close)
            }
            contour++
            if (!measure.nextContour()) break
        }
        if (segs.isEmpty()) return null
        return PathData(segs)
    }
}
