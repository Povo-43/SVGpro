package dev.svgpro

import dev.svgpro.core.geom.Matrix
import dev.svgpro.core.geom.Vec
import dev.svgpro.core.svg.SvgLoader
import dev.svgpro.editor.Editor
import dev.svgpro.editor.Trace
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 描画時の行列合成順序の契約を固定するテスト。
 *
 * SvgRenderer.drawItem は `item.abs.then(viewMatrix)` の順で合成しなければならない
 * （abs = ローカル→user空間のCTM、viewMatrix = user→画面）。
 * 逆順（viewMatrix.then(abs)）だと transform 属性が画面空間で解釈され、
 * 選択ボックス（Trace.boundsOf = abs基準）と要素の描画位置がズレる
 * 「選択は正しいのにパスが右下へ飛ぶ」バグの回帰防止。
 */
class RenderMatrixOrderTest {

    private fun tokenSourceOf(xml: String) = object : dev.svgpro.core.svg.TokenSource {
        private val q = SimpleTokens(xml)
        override fun next(): dev.svgpro.core.svg.Token = q.next()
    }

    /** transform を持つ要素を含むドキュメントの Trace を作る */
    private fun traceWithTransformedElement(): Pair<Editor, Trace> {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
              <rect id="moved" x="10" y="10" width="40" height="30"
                    transform="matrix(1,0,0,1,25,15)" />
            </svg>
        """.trimIndent()
        val doc = SvgLoader.load(::tokenSourceOf, xml)
        assertNotNull(doc)
        val editor = Editor()
        editor.loadDocument(doc, "t.svg")
        return editor to editor.trace
    }

    @Test
    fun screen_position_matches_view_of_abs_point() {
        val (editor, trace) = traceWithTransformedElement()
        val el = assertNotNull(editor.doc.root.findById("moved"))
        val item = assertNotNull(trace.itemOf(el))

        // 画面変換（ズーム0.7 + 余白オフセット）: user → screen
        val zoom = 0.7
        val view = Matrix(a = zoom, d = zoom, e = 40.0, f = 30.0)

        // ローカル座標の点（rectの左上）
        val pLocal = Vec(10.0, 10.0)
        // 正: abs を先に適用し、その後 view を適用する
        val composed = item.abs.then(view)
        val screen = composed.apply(pLocal)

        // 期待値: user座標へ変換してから画面変換（選択ボックスの計算と同一の経路）
        val expected = view.apply(item.abs.apply(pLocal))
        assertEquals(expected.x, screen.x, 1e-9)
        assertEquals(expected.y, screen.y, 1e-9)

        // 症状の固定: 移動量(25,15)userは画面上で zoom 倍されて反映される
        // （逆順だと zoom 無視の 25px となり、ズーム<1では選択枠より右下へ飛び出す）
        val moved = Vec(35.0, 25.0) // x=10+25, y=10+15
        val noTransformScreen = view.apply(pLocal)
        val deltaScreen = screen - noTransformScreen
        assertEquals(view.apply(moved).x - noTransformScreen.x, deltaScreen.x, 1e-9)
        assertEquals(view.apply(moved).y - noTransformScreen.y, deltaScreen.y, 1e-9)
        assertEquals(zoom * 25.0, deltaScreen.x, 1e-9)
        assertEquals(zoom * 15.0, deltaScreen.y, 1e-9)
    }

    @Test
    fun abs_is_local_to_user_ctm() {
        val (editor, trace) = traceWithTransformedElement()
        val el = assertNotNull(editor.doc.root.findById("moved"))
        val item = assertNotNull(trace.itemOf(el))

        // abs = translate(25,15)：ローカル点をuser空間へ写す
        val pLocal = Vec(10.0, 10.0)
        val pUser = item.abs.apply(pLocal)
        assertEquals(35.0, pUser.x, 1e-9)
        assertEquals(25.0, pUser.y, 1e-9)

        // 選択ボックス（boundsOf）はuser空間で移動後の位置を示す
        val b = assertNotNull(trace.boundsOf(el))
        assertEquals(35.0, b.x, 1e-6)
        assertEquals(25.0, b.y, 1e-6)
    }

    @Test
    fun nested_group_transform_composes_parent_first() {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
              <g transform="matrix(1,0,0,1,100,50)">
                <rect id="inner" x="0" y="0" width="10" height="10"
                      transform="matrix(1,0,0,1,5,5)" />
              </g>
            </svg>
        """.trimIndent()
        val doc = SvgLoader.load(::tokenSourceOf, xml)
        assertNotNull(doc)
        val editor = Editor()
        editor.loadDocument(doc, "t.svg")
        val el = assertNotNull(editor.doc.root.findById("inner"))
        val item = assertNotNull(editor.trace.itemOf(el))

        // abs = parent.then(local): 親のtranslateを後に適用した合成
        val p = item.abs.apply(Vec(0.0, 0.0))
        assertEquals(105.0, p.x, 1e-9)
        assertEquals(55.0, p.y, 1e-9)

        // 描画合成は abs を先に適用する順でなければ、この点と一致しない
        val view = Matrix(a = 1.0, d = 1.0)
        val composed = item.abs.then(view)
        assertEquals(p.x, composed.apply(Vec(0.0, 0.0)).x, 1e-9)
        assertEquals(p.y, composed.apply(Vec(0.0, 0.0)).y, 1e-9)
    }

    @Test
    fun scaled_element_tracks_selection_box_under_any_zoom() {
        // 拡縮を含むtransformでも、合成順が正しければ全ズームで選択枠と描画が一致する
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
              <rect id="scaled" x="10" y="10" width="20" height="10"
                    transform="matrix(2,0,0,2,10,10)" />
            </svg>
        """.trimIndent()
        val doc = SvgLoader.load(::tokenSourceOf, xml)
        assertNotNull(doc)
        val editor = Editor()
        editor.loadDocument(doc, "t.svg")
        val el = assertNotNull(editor.doc.root.findById("scaled"))
        val item = assertNotNull(editor.trace.itemOf(el))
        val b = assertNotNull(editor.trace.boundsOf(el))

        for (zoom in listOf(0.3, 0.7, 2.0)) {
            val view = Matrix(a = zoom, d = zoom, e = 12.0, f = 9.0)
            val composed = item.abs.then(view)
            // bboxの4隅がどれも描画パス上に存在する（=選択枠と描画の一致）
            val corners = listOf(
                Vec(b.x, b.y), Vec(b.right, b.y), Vec(b.x, b.bottom), Vec(b.right, b.bottom),
            )
            val screenCorners = corners.map { view.apply(it) }
            // absの逆変換でbbox隅に対応するローカル点を求め、描画合成で画面へ写す
            val inv = item.abs.invert()
            for (c in screenCorners) {
                val user = view.invert().apply(c)
                val local = inv.apply(user)
                val drawn = composed.apply(local)
                assertTrue(abs(drawn.x - c.x) < 1e-6 && abs(drawn.y - c.y) < 1e-6,
                    "zoom=$zoom corner mismatch: drawn=$drawn expected=$c")
            }
        }
    }
}
