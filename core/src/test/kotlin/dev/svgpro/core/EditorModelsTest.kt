package dev.svgpro.core

import dev.svgpro.core.geom.Bounds
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.model.ClipMaskUtil
import dev.svgpro.core.model.Color
import dev.svgpro.core.model.ColorOps
import dev.svgpro.core.model.Guide
import dev.svgpro.core.model.GradientModel
import dev.svgpro.core.model.PatternUtil
import dev.svgpro.core.model.Shapes
import dev.svgpro.core.model.StyleUtil
import dev.svgpro.core.svg.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ガイド・グラデーション・クリップ/マスク・色操作のテスト */
class EditorModelsTest {

    // ―――― ガイド ――――

    @Test
    fun guides_roundtrip() {
        val guides = listOf(
            Guide(horizontal = true, pos = 100.5),
            Guide(horizontal = false, pos = 20.0, locked = true),
            Guide(horizontal = true, pos = -3.25, visible = false),
        )
        val encoded = Guide.Codec.encode(guides)
        assertNotNull(encoded)
        val decoded = Guide.Codec.decode(encoded)
        assertEquals(3, decoded.size)
        assertEquals(guides[0], decoded[0])
        assertEquals(guides[1], decoded[1])
        assertEquals(guides[2], decoded[2])
        // 空はnull
        assertEquals(null, Guide.Codec.encode(emptyList()))
        assertEquals(0, Guide.Codec.decode(null).size)
        // 不正データは無視（有効な h10 と v5 は保持される）
        assertEquals(2, Guide.Codec.decode("h10|x:bad|v5").size)
    }

    // ―――― グラデーション ――――

    private fun newGradient(radial: Boolean): Pair<Element, GradientModel> {
        val defs = Element("defs")
        val g = Element(if (radial) "radialGradient" else "linearGradient")
        g.setAttr("id", "grad1")
        val s1 = Element("stop")
        s1.setAttr("offset", "0")
        s1.setAttr("stop-color", "#ff0000")
        val s2 = Element("stop")
        s2.setAttr("offset", "1")
        s2.setAttr("stop-color", "#0000ff")
        s2.setAttr("stop-opacity", "0.5")
        g.children.add(s1)
        g.children.add(s2)
        defs.children.add(g)
        return g to GradientModel.of(g)!!
    }

    @Test
    fun gradient_parse_and_write_roundtrip() {
        val (el, model) = newGradient(false)
        assertEquals(2, model.stops.size)
        assertEquals(0xFFFF0000.toInt(), model.stops[0].argb)
        val expectedAlphaBlue = (Math.round(0.5 * 255).toInt() shl 24) or 0x0000FF
        assertEquals(expectedAlphaBlue, model.stops[1].argb) // α0.5の青
        // 編集して書き戻す
        model.stops[0].offset = 0.2
        model.stops.add(GradientModel.Stop(0.6, 0xFF00FF00.toInt()))
        model.write()
        // 再読み込み
        val model2 = GradientModel.of(el)!!
        assertEquals(3, model2.stops.size)
        assertEquals(0.2, model2.stops[0].offset, 1e-9)
        assertEquals(0xFF00FF00.toInt(), model2.stops[1].argb)
        assertEquals(expectedAlphaBlue, model2.stops[2].argb)
        assertEquals(0.6, model2.stops[1].offset, 1e-9)
    }

    @Test
    fun gradient_add_remove_stop() {
        val (_, model) = newGradient(true)
        // 中間ストップ追加 → 補間色
        val idx = model.addStop(0.5)
        assertEquals(1, idx)
        assertEquals(3, model.stops.size)
        val mid = model.stops[1]
        // 赤→青の補間: RGB双方が中間値
        assertTrue((mid.argb shr 16 and 0xFF) in 1..254)
        assertTrue((mid.argb and 0xFF) in 1..254)
        // 削除は可能（3→2）
        assertTrue(model.removeStop(1))
        // 最低2ストップは保持（2→1は不可）
        assertFalse(model.removeStop(0))
        assertEquals(2, model.stops.size)
    }

    @Test
    fun gradient_handle_points_roundtrip() {
        val (_, linear) = newGradient(false)
        val b = Bounds(10.0, 20.0, 100.0, 50.0)
        val (s, e) = linear.handlePoints(b)
        assertEquals(Vec(10.0, 20.0), s)
        assertEquals(Vec(110.0, 20.0), e) // x2=1, y2=0
        // ハンドルから逆に設定
        linear.setFromHandles(b, Vec(30.0, 40.0), Vec(80.0, 60.0))
        val (s2, e2) = linear.handlePoints(b)
        assertTrue(s2.distanceTo(Vec(30.0, 40.0)) < 1e-6)
        assertTrue(e2.distanceTo(Vec(80.0, 60.0)) < 1e-6)

        val (_, radial) = newGradient(true)
        val (c, edge) = radial.handlePoints(b)
        assertEquals(Vec(60.0, 45.0), c) // cx=0.5, cy=0.5
        assertTrue(edge.x > c.x)
    }

    // ―――― クリップ / マスク ――――

    @Test
    fun clip_path_create_apply_release() {
        val root = Element("svg")
        val defs = Element("defs")
        root.children.add(defs)
        val clipShape = Element("circle")
        clipShape.setAttr("cx", "50")
        clipShape.setAttr("cy", "50")
        clipShape.setAttr("r", "20")
        val target = Element("path")
        target.setAttr("d", "M0 0 L100 0 L100 100 Z")

        val clip = ClipMaskUtil.createClipPath(
            id = "clip1",
            sources = listOf(clipShape),
            absOf = { dev.svgpro.core.geom.Matrix() },
            targetAbs = dev.svgpro.core.geom.Matrix(),
        )
        defs.children.add(clip)
        assertEquals(1, clip.childElements().size)
        assertTrue(clip.childElements()[0].attr("d")!!.contains("C")) // 円はcubic近似パス化

        ClipMaskUtil.applyClip(target, "clip1")
        assertEquals("url(#clip1)", target.attr("clip-path"))
        // 解除 → 未使用ならdefsから消える
        ClipMaskUtil.releaseClip(target, root, "clip1")
        assertEquals(null, target.attr("clip-path"))
        assertEquals(0, defs.childElements().size)
    }

    @Test
    fun mask_create_has_white_shapes_and_black_bg() {
        val clipShape = Element("rect")
        clipShape.setAttr("x", "10")
        clipShape.setAttr("y", "10")
        clipShape.setAttr("width", "30")
        clipShape.setAttr("height", "30")
        val target = Element("path")
        target.setAttr("d", "M0 0 L100 0 L100 100 Z")
        val mask = ClipMaskUtil.createMask(
            id = "m1",
            sources = listOf(clipShape),
            absOf = { dev.svgpro.core.geom.Matrix() },
            targetAbs = dev.svgpro.core.geom.Matrix(),
            targetGeometryBounds = Bounds(0.0, 0.0, 100.0, 100.0),
        )
        assertNotNull(mask)
        val children = mask.childElements()
        assertTrue(children.size >= 2)
        assertEquals("black", children[0].attr("fill"))
        assertEquals("white", children[1].attr("fill"))
        // 幾何がtargetローカル座標へ焼き込まれている（identityなので同じ位置）
        val geo = Shapes.geometryOf(children[1])!!
        val b = geo.bounds(0.01)!!
        assertTrue(b.x > 5.0 && b.x < 15.0)
    }

    // ―――― fill-rule / 色操作 ――――

    @Test
    fun fill_rule_edit() {
        val el = Element("path")
        el.setAttr("d", "M0 0 L10 0 L10 10 Z")
        assertEquals("nonzero", StyleUtil.fillRule(el))
        StyleUtil.setFillRule(el, "evenodd")
        assertEquals("evenodd", StyleUtil.fillRule(el))
        StyleUtil.setFillRule(el, "nonzero")
        assertEquals("nonzero", StyleUtil.fillRule(el))
        assertEquals(null, el.attr("fill-rule"))
    }

    @Test
    fun color_collect_and_replace() {
        val root = Element("svg")
        val a = Element("rect")
        a.setAttr("fill", "#ff0000")
        a.setAttr("stroke", "rgb(255,0,0)")
        val g = Element("linearGradient")
        val s = Element("stop")
        s.setAttr("stop-color", "#ff0000")
        g.children.add(s)
        val group = Element("g")
        group.children.add(a)
        root.children.add(group)
        root.children.add(g)

        val colors = ColorOps.collectColors(root)
        assertTrue(colors.contains(0xFFFF0000.toInt()))

        val count = ColorOps.replaceColor(root, 0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        assertEquals(2, count) // a（fill+stroke）と グラデーションg（stop）の2要素
        assertEquals("rgb(0,255,0)", StyleUtil.getProperty(a, "fill"))
        assertEquals("rgb(0,255,0)", StyleUtil.getProperty(a, "stroke"))
        assertEquals("rgb(0,255,0)", StyleUtil.getProperty(s, "stop-color"))

        val users = ColorOps.elementsUsingColor(root, 0xFF00FF00.toInt())
        assertEquals(listOf(a), users)
    }

    @Test
    fun pattern_from_elements() {
        val src = Element("circle")
        src.setAttr("cx", "10")
        src.setAttr("cy", "10")
        src.setAttr("r", "5")
        val pattern = PatternUtil.createPattern(
            id = "pat1",
            tile = Bounds(0.0, 0.0, 20.0, 20.0),
            sources = listOf(src),
            bake = { dev.svgpro.core.geom.Matrix() },
        )
        assertEquals("pattern", pattern.tag)
        assertEquals("20", pattern.attr("width"))
        assertEquals("userSpaceOnUse", pattern.attr("patternUnits"))
        assertEquals(1, pattern.childElements().size)
        val target = Element("path")
        target.setAttr("d", "M0 0 L10 10 Z")
        PatternUtil.applyPattern(target, "pat1")
        assertEquals("url(#pat1)", StyleUtil.getProperty(target, "fill"))
        PatternUtil.setTransform(pattern, scale = 2.0, rotationDeg = 45.0, offsetX = 0.0, offsetY = 0.0)
        assertTrue(pattern.attr("patternTransform")!!.contains("rotate(45)"))
        assertTrue(pattern.attr("patternTransform")!!.contains("scale(2)"))
    }
}
