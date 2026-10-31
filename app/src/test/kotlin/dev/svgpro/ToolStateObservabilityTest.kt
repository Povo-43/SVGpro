package dev.svgpro

import androidx.compose.runtime.snapshots.Snapshot
import dev.svgpro.core.geom.Vec
import dev.svgpro.editor.Editor
import dev.svgpro.editor.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UIから直接読まれるEditor状態がCompose Snapshot Stateとして購読可能であることを
 * 固定するテスト。
 *
 * ツールボタンのactive表示は Compose が `editor.tool` の読み取りを購読して成立する。
 * プレーンなフィールドのままだと strong skipping 下で再構成が起きず
 * 「選択中ツールがハイライトされない」バグになる。
 *  - readObserver は状態の「読み取り」を、writeObserver は状態の「書き込み」を観測する。
 *  - 読み取り/書き込みのいずれも観測されない = プレーンフィールド（Composeから見えない）。
 */
class ToolStateObservabilityTest {

    @Test
    fun tool_reads_are_snapshot_backed() {
        val editor = Editor()
        var reads = 0
        Snapshot.observe(readObserver = { reads++ }, writeObserver = null) {
            editor.tool
        }
        assertTrue(reads >= 1, "editor.tool reads must be observed (snapshot state)")
    }

    @Test
    fun tool_writes_are_snapshot_backed_and_skip_same_value() {
        val editor = Editor()
        var writes = 0
        Snapshot.observe(readObserver = null, writeObserver = { writes++ }) {
            editor.tool = ToolType.PEN
            assertEquals(1, writes)
            // 同値の代入は構造的等価により通知しない（不要な再構成を起こさない）
            editor.tool = ToolType.PEN
            assertEquals(1, writes)
            editor.tool = ToolType.NODE
            assertEquals(2, writes)
        }
        assertEquals(ToolType.NODE, editor.tool)
    }

    @Test
    fun ui_flags_are_snapshot_states() {
        val editor = Editor()
        var writes = 0
        Snapshot.observe(readObserver = null, writeObserver = { writes++ }) {
            editor.showGrid = true
            editor.showRulers = true
            editor.snapToGrid = true
            editor.penOnly = true
            assertEquals(4, writes)
            // 同値再代入では増えない
            editor.showGrid = true
            assertEquals(4, writes)
        }
    }

    @Test
    fun dirty_write_is_snapshot_backed() {
        val editor = Editor()
        var dirtyWrites = 0
        Snapshot.observe(readObserver = null, writeObserver = { dirtyWrites++ }) {
            editor.newDocument(100.0, 100.0) // dirty = false
            editor.createShape(ToolType.RECT, Vec(0.0, 0.0), Vec(10.0, 10.0)) // dirty = true
        }
        assertTrue(editor.dirty, "editing must set dirty")
        assertTrue(dirtyWrites >= 1, "dirty must be snapshot-backed, writes=$dirtyWrites")
    }
}
