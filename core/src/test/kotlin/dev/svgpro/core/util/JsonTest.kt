package dev.svgpro.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonTest {

    @Test
    fun stringify_map() {
        val map = mapOf("name" to "Page 1", "w" to "512", "h" to "512")
        val json = Json.stringify(map)
        assertNotNull(json)
        assertEquals("{\"name\":\"Page 1\",\"w\":\"512\",\"h\":\"512\"}", json)
    }

    @Test
    fun stringify_list_of_maps() {
        val list = listOf(
            mapOf("name" to "A", "x" to "0"),
            mapOf("name" to "B", "x" to "100"),
        )
        val json = Json.stringify(list)
        assertEquals("[{\"name\":\"A\",\"x\":\"0\"},{\"name\":\"B\",\"x\":\"100\"}]", json)
    }

    @Test
    fun parse_map() {
        val json = """{"name":"Page 1","x":0,"w":512}"""
        val parsed = Json.parse(json)
        assertNotNull(parsed)
        val m = parsed as Map<*, *>
        assertEquals("Page 1", m["name"])
        assertEquals(0, m["x"])
        assertEquals(512, m["w"])
    }

    @Test
    fun parse_list() {
        val json = """[{"name":"A"},{"name":"B"}]"""
        val parsed = Json.parse(json)
        assertNotNull(parsed)
        assertEquals(listOf(mapOf("name" to "A"), mapOf("name" to "B")), parsed)
    }

    @Test
    fun parse_null_and_empty() {
        assertNull(Json.parse(null))
        assertNull(Json.parse(""))
        assertNull(Json.parse("   "))
    }

    @Test
    fun roundtrip() {
        val original = listOf(
            mapOf("id" to "artboard_0", "name" to "Page 1", "x" to "0", "y" to "0", "w" to "512", "h" to "512"),
            mapOf("id" to "artboard_1", "name" to "Page 2", "x" to "512", "y" to "0", "w" to "512", "h" to "512"),
        )
        val json = Json.stringify(original)
        val parsed = Json.parse(json) as List<*>
        assertEquals(2, parsed.size)
        assertEquals("Page 1", (parsed[0] as Map<*, *>)["name"])
        assertEquals("Page 2", (parsed[1] as Map<*, *>)["name"])
    }
}
