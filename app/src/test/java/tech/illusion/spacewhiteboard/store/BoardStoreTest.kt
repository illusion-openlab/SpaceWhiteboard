package tech.illusion.spacewhiteboard.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.illusion.spacewhiteboard.board.BoardDoc
import tech.illusion.spacewhiteboard.board.ItemId
import tech.illusion.spacewhiteboard.board.Pt
import tech.illusion.spacewhiteboard.board.Stroke
import java.io.File

class BoardStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun docWith(n: Int): BoardDoc {
        var d = BoardDoc()
        for (i in 1..n) d = d.add(Stroke(ItemId(i.toLong()), 0xFF000000.toInt(), 2f, listOf(Pt(i.toFloat(), 0f))))
        return d
    }

    @Test
    fun `save then load round trips the document`() {
        val f = File(tmp.newFolder("boards"), "main.wbd")
        val store = BoardStore(f)
        store.save(docWith(3))

        val r = store.load()
        assertTrue(r is LoadResult.Ok)
        assertEquals(3, (r as LoadResult.Ok).doc.items.size)
        assertEquals(0, r.skippedLines)
    }

    @Test
    fun `load returns Empty when the file does not exist yet`() {
        val f = File(tmp.newFolder("boards"), "missing.wbd")
        assertTrue(BoardStore(f).load() is LoadResult.Empty)
    }

    @Test
    fun `save creates missing parent directories`() {
        val f = File(tmp.newFolder("root"), "deep/nested/main.wbd")
        BoardStore(f).save(docWith(1))
        assertTrue(f.exists())
    }

    @Test
    fun `save leaves no temp file behind`() {
        val dir = tmp.newFolder("boards")
        val f = File(dir, "main.wbd")
        BoardStore(f).save(docWith(2))
        assertFalse(File(dir, "main.wbd.tmp").exists())
    }

    @Test
    fun `overwriting an existing board replaces its content`() {
        val f = File(tmp.newFolder("boards"), "main.wbd")
        val store = BoardStore(f)
        store.save(docWith(5))
        store.save(docWith(1))
        assertEquals(1, (store.load() as LoadResult.Ok).doc.items.size)
    }

    @Test
    fun `a corrupt file is reported, backed up, and never silently cleared`() {
        val dir = tmp.newFolder("boards")
        val f = File(dir, "main.wbd")
        f.writeText("this is not a whiteboard file at all")

        val r = BoardStore(f).load()
        assertTrue(r is LoadResult.Corrupt)

        // 原文件必须原封不动 —— 静默清空等于把损坏"修复"成永久丢失
        assertEquals("this is not a whiteboard file at all", f.readText())
        // 且留下一份备份供人工抢救
        val backup = File(dir, "main.wbd.corrupt")
        assertTrue(backup.exists())
        assertEquals((r as LoadResult.Corrupt).backupPath, backup.path)
    }

    @Test
    fun `unknown future line prefixes load fine and are counted`() {
        val f = File(tmp.newFolder("boards"), "main.wbd")
        f.writeText("wbd1\ns ff000000 1.0 0.0,0.0\nt ff000000 24.0 1.0,1.0 300.0 SGk=\n")

        val r = BoardStore(f).load()
        assertTrue(r is LoadResult.Ok)
        assertEquals(1, (r as LoadResult.Ok).doc.items.size)
        assertEquals(1, r.skippedLines)
    }

    // --- Extra guard test beyond the brief's 7 ---
    // The brief only tests that a *successful* save leaves no .tmp behind. It does not test
    // the scenario the atomic-write design exists for: a leftover .tmp from a previously
    // crashed write (process killed after opening the temp file but before the rename).
    @Test
    fun `save overwrites a stale leftover tmp file from a previous crashed write`() {
        val dir = tmp.newFolder("boards")
        val f = File(dir, "main.wbd")
        val store = BoardStore(f)

        // Existing valid board on disk.
        store.save(docWith(5))

        // Simulate a crash mid-write: a stale .tmp file full of garbage sits next to it.
        val staleTmp = File(dir, "main.wbd${BoardStore.TMP_SUFFIX}")
        staleTmp.writeText("garbage from a killed process, not valid wbd1 content")
        assertTrue(staleTmp.exists())

        // A fresh save must succeed and produce the new content, not the garbage or the old content.
        store.save(docWith(2))

        val r = store.load()
        assertTrue(r is LoadResult.Ok)
        assertEquals(2, (r as LoadResult.Ok).doc.items.size)
        assertFalse(staleTmp.exists())
    }
}
