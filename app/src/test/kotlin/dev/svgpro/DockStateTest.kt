package dev.svgpro

import dev.svgpro.ui.DockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ドック式パネルシステム（タブ化・表示切替・グループ移動）のロジックテスト */
class DockStateTest {

    @Test
    fun default_layout_has_all_panels_tabbed() {
        val dock = DockState()
        assertEquals(listOf("props", "layers", "history", "navigator"), dock.openPanels())
        assertEquals(1, dock.groups.size)
        assertEquals("props", dock.activePanel)
        assertTrue(dock.isOpen("layers"))
    }

    @Test
    fun toggle_closes_and_reopens_from_menu() {
        val dock = DockState()
        dock.toggle("layers")
        assertFalse(dock.isOpen("layers"))
        dock.toggle("layers")
        assertTrue(dock.isOpen("layers"))
        // 再オープン時はアクティブになる
        assertEquals("layers", dock.activePanel)
    }

    @Test
    fun close_last_tab_removes_group_and_falls_back_active() {
        val dock = DockState()
        dock.close("props")
        dock.close("layers")
        dock.close("history")
        // navigator だけ残る
        assertEquals(listOf("navigator"), dock.openPanels())
        dock.close("navigator")
        assertEquals(0, dock.groups.size)
        assertEquals(0, dock.weights.size)
        // activePanel は開いているパネルが無ければ既定へ
        assertEquals("props", dock.activePanel)
        // 空のドックへメニューから再オープン → 新グループ
        dock.open("props")
        assertEquals(1, dock.groups.size)
        assertTrue(dock.isOpen("props"))
    }

    @Test
    fun moveTab_separates_into_new_group() {
        val dock = DockState()
        dock.moveTab("layers", dock.groups.size, null)
        assertEquals(2, dock.groups.size)
        assertEquals(listOf("layers"), dock.groups[1].tabs)
        assertEquals("layers", dock.groups[1].active)
        // 重みはグループ数と同期する
        assertEquals(2, dock.weights.size)
    }

    @Test
    fun moveTab_merges_into_another_group() {
        val dock = DockState()
        dock.moveTab("layers", dock.groups.size, null) // 分離
        dock.moveTab("layers", 0, "props")             // props の前に戻す
        assertEquals(1, dock.groups.size)
        assertEquals(listOf("layers", "props", "history", "navigator"), dock.groups[0].tabs)
        assertEquals("layers", dock.groups[0].active)
    }

    @Test
    fun moveTab_from_removed_source_adjusts_target_index() {
        val dock = DockState()
        dock.moveTab("layers", dock.groups.size, null) // 分離 → groups=[G0, G1(layers)]
        dock.moveTab("layers", 1, null)                // 自分自身のグループへ（実質変化なし）
        assertEquals(2, dock.groups.size)
    }

    @Test
    fun adjustWeights_clamps_minimum() {
        val dock = DockState()
        dock.moveTab("layers", dock.groups.size, null)
        val before = dock.weights.toList()
        // 極端なドラッグでも最小比を下回らない
        dock.adjustWeights(0, 10f)
        assertTrue(dock.weights[0] >= 0.08f)
        assertTrue(dock.weights[1] >= 0.08f)
        dock.adjustWeights(0, -10f)
        assertTrue(dock.weights[0] >= 0.08f)
        assertEquals(before.size, dock.weights.size)
    }

    @Test
    fun open_appends_to_last_group() {
        val dock = DockState()
        dock.close("history")
        dock.open("history")
        // 末尾グループの末尾タブとして戻る
        assertEquals(listOf("props", "layers", "navigator", "history"), dock.groups.last().tabs)
        assertEquals("history", dock.activePanel)
    }
}
