package dev.svgpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SvgDocumentTest {

    @Test
    fun create_has_one_artboard() {
        val doc = SvgDocument.create(512.0, 512.0)
        assertEquals(1, doc.artboards.size)
        assertEquals("Page 1", doc.artboards[0].name)
        assertEquals(0.0, doc.artboards[0].x)
        assertEquals(0.0, doc.artboards[0].y)
        assertEquals(512.0, doc.artboards[0].w)
        assertEquals(512.0, doc.artboards[0].h)
    }

    @Test
    fun add_and_remove_artboard() {
        val doc = SvgDocument.create(100.0, 100.0)
        doc.addArtboard("Page 2", 100.0, 0.0, 100.0, 100.0)
        assertEquals(2, doc.artboards.size)
        assertEquals("Page 2", doc.artboards[1].name)

        val removed = doc.removeArtboard(1)
        assertTrue(removed)
        assertEquals(1, doc.artboards.size)
    }

    @Test
    fun cannot_remove_last_artboard() {
        val doc = SvgDocument.create(100.0, 100.0)
        val removed = doc.removeArtboard(0)
        assertTrue(!removed)
        assertEquals(1, doc.artboards.size)
    }

    @Test
    fun rename_artboard() {
        val doc = SvgDocument.create(100.0, 100.0)
        val renamed = doc.renameArtboard(0, "Cover")
        assertTrue(renamed)
        assertEquals("Cover", doc.artboards[0].name)
    }

    @Test
    fun artboard_viewbox() {
        val doc = SvgDocument.create(200.0, 200.0)
        val vb = doc.artboardViewBox(0)
        assertNotNull(vb)
        assertEquals(0.0, vb!!.minX)
        assertEquals(0.0, vb.minY)
        assertEquals(200.0, vb.width)
        assertEquals(200.0, vb.height)
    }

    @Test
    fun save_and_restore_artboards() {
        val doc = SvgDocument.create(100.0, 100.0)
        doc.addArtboard("Page 2", 100.0, 0.0, 100.0, 100.0)
        doc.saveArtboards()

        val json = doc.root.attr("data-svgpro-artboards")
        assertNotNull(json)

        val doc2 = SvgDocument.create(100.0, 100.0)
        doc2.root.setAttr("data-svgpro-artboards", json)
        doc2.restoreArtboards()
        assertEquals(2, doc2.artboards.size)
        assertEquals("Page 1", doc2.artboards[0].name)
        assertEquals("Page 2", doc2.artboards[1].name)
        assertEquals(100.0, doc2.artboards[1].x)
        assertEquals(100.0, doc2.artboards[1].w)
    }
}
