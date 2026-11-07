package dev.svgpro

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.Guide
import dev.svgpro.editor.Editor
import dev.svgpro.editor.ToolType
import dev.svgpro.editor.selectionGaps
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ロゴデザイン向けルーラー強化（計測ツール・角度ガイド・中心ガイド・回転スナップ角）の回帰テスト */
class MeasureRulerTest {

    private fun newEditor(): Editor {
        val e = Editor()
        e.newDocument(200.0, 200.0)
        return e
    }

    // ―――― 計測（メジャー）ツール ――――

    @Test
    fun measureAngle_displayIsCounterClockwisePositive() {
        val e = newEditor()
        e.commitMeasure(Vec(0.0, 0.0), Vec(10.0, 0.0))   // 右 → 0°
        assertEquals(0.0, e.measureAngleDeg()!!, 1e-6)

        e.commitMeasure(Vec(0.0, 0.0), Vec(0.0, -10.0))  // 画面上向き → +90°
        assertEquals(90.0, e.measureAngleDeg()!!, 1e-6)

        e.commitMeasure(Vec(0.0, 0.0), Vec(0.0, 10.0))   // 画面下向き → -90°
        assertEquals(-90.0, e.measureAngleDeg()!!, 1e-6)
    }

    @Test
    fun commitMeasure_tooShort_clearsLastMeasure() {
        val e = newEditor()
        e.commitMeasure(Vec(10.0, 10.0), Vec(10.0 + 1e-9, 10.0))
        assertNull(e.lastMeasure)
    }

    @Test
    fun clearMeasure_resetsAll() {
        val e = newEditor()
        e.commitMeasure(Vec(0.0, 0.0), Vec(10.0, 0.0))
        e.clearMeasure()
        assertNull(e.measureStart)
        assertNull(e.measureEnd)
        assertNull(e.lastMeasure)
    }

    // ―――― 計測からの角度ガイド生成 ――――

    @Test
    fun createMeasureGuide_addsAngledGuideThroughStart() {
        val e = newEditor()
        // 画面上向き45°=user空間では -45°（Y下向き）
        e.commitMeasure(Vec(10.0, 50.0), Vec(50.0, 10.0))
        assertTrue(e.createMeasureGuide())
        val guide = e.guides.lastOrNull { it.isAngled }
        assertNotNull(guide)
        val pt = guide!!.anchor()
        assertEquals(10.0, pt.x, 1e-6)
        assertEquals(50.0, pt.y, 1e-6)
        // ガイド線上に計測終点が乗る
        assertTrue(guide.distanceTo(Vec(50.0, 10.0)) < 1e-6)
        // 保存属性へ roundtrip 可能（codec）
        val enc = guide.encode()
        val back = Guide.decode(enc)
        assertNotNull(back)
        assertTrue(back!!.isAngled)
    }

    // ―――― アートボード中心ガイド ――――

    @Test
    fun addCenterGuides_createsHVatCenter() {
        val e = newEditor()
        val before = e.guides.size
        e.addCenterGuides()
        assertEquals(before + 2, e.guides.size)
        val h = e.guides.lastOrNull { it.horizontal && !it.isAngled }
        val v = e.guides.lastOrNull { !it.horizontal && !it.isAngled }
        assertNotNull(h)
        assertNotNull(v)
        assertEquals(100.0, h!!.pos, 1e-6)
        assertEquals(100.0, v!!.pos, 1e-6)
    }

    // ―――― 回転スナップ角 ――――

    @Test
    fun rotateSnapStep_defaultIs15_andConfigurable() {
        val e = newEditor()
        assertEquals(15.0, e.rotateSnapStep)
        e.rotateSnapStep = 5.0
        assertEquals(5.0, e.rotateSnapStep)
        // ドキュメント再読込で設定が維持される（アプリ設定のため）
        e.newDocument(100.0, 100.0)
        assertEquals(5.0, e.rotateSnapStep)
    }

    // ―――― 2選択間の隙間（ステータスバー表示元） ――――

    @Test
    fun selectionGaps_measuresBBoxDistance() {
        val e = newEditor()
        e.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(20.0, 20.0))
        e.createShape(ToolType.RECT, Vec(40.0, 0.0), Vec(60.0, 20.0))
        // 両方を選択（create後は最後の1個のみ選択されるため）
        e.selectAllInBox(dev.svgpro.core.geom.Bounds(-1e9, -1e9, 1e18, 1e18), additive = false)
        val gaps = e.selectionGaps()
        assertNotNull(gaps)
        assertEquals(20.0, gaps!!.first, 1e-6) // 水平隙間
        assertEquals(0.0, gaps.second, 1e-6)   // 垂直は重なり（0）
        assertFalse(abs(gaps.first) > 21.0)
    }
}
