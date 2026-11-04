package dev.svgpro.core

import dev.svgpro.core.geom.Vec
import dev.svgpro.core.path.NodePt
import dev.svgpro.core.path.SegType
import dev.svgpro.core.path.SubpathNodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoveNodeBugCheck {
    @Test
    fun removeFirstNodeOpenPathShouldNotCrash() {
        val nodes = arrayListOf(NodePt(Vec(0.0, 0.0), null, null), NodePt(Vec(10.0, 0.0), null, null), NodePt(Vec(10.0, 10.0), null, null))
        val types = arrayListOf(SegType.LINE, SegType.LINE)
        val m = SubpathNodes(nodes, types, closed = false)
        val ok = m.removeNode(0)
        assertTrue(ok)
        assertEquals(2, m.nodes.size)
        assertEquals(Vec(10.0, 0.0), m.nodes[0].p)
        assertEquals(Vec(10.0, 10.0), m.nodes[1].p)
        assertEquals(1, m.segTypes.size)
    }

    @Test
    fun removeLastNodeOpenPathShouldNotCrash() {
        val nodes = arrayListOf(NodePt(Vec(0.0, 0.0), null, null), NodePt(Vec(10.0, 0.0), null, null), NodePt(Vec(10.0, 10.0), null, null))
        val types = arrayListOf(SegType.LINE, SegType.LINE)
        val m = SubpathNodes(nodes, types, closed = false)
        val ok = m.removeNode(2)
        assertTrue(ok)
        assertEquals(2, m.nodes.size)
        assertEquals(Vec(0.0, 0.0), m.nodes[0].p)
        assertEquals(Vec(10.0, 0.0), m.nodes[1].p)
        assertEquals(1, m.segTypes.size)
    }
}
