package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextItemTest {

    // `w` here is the measured ink extent (eBoard) — what `bounds()` actually reads.
    // `wBoard` (the wrap constraint) is set to the same value: irrelevant to every
    // assertion in this file, which only exercises bounds()/contentBounds()/eraser behavior.
    private fun text(anchor: Pt, w: Float, h: Float) = TextItem(
        id = ItemId(1), text = "hello", colorArgb = 0xFF112233.toInt(),
        sizeBoard = 24f, anchor = anchor, wBoard = w, hBoard = h, eBoard = w,
    )

    @Test
    fun `bounds start at the anchor and span the stored extent`() {
        val b = text(Pt(100f, 50f), 200f, 60f).bounds()
        assertEquals(100f, b.left, 1e-4f)
        assertEquals(50f, b.top, 1e-4f)
        assertEquals(300f, b.right, 1e-4f)
        assertEquals(110f, b.bottom, 1e-4f)
    }

    @Test
    fun `bounds width follows the measured extent, not the larger wrap constraint`() {
        // wBoard（换行约束）远大于 eBoard（测量出的墨迹宽度）——一个短文本在一个很宽的换行框里
        // 测量出来的样子。bounds() 必须贴着墨迹本身，不能被换行约束撑大（1b-2 复审修复项）。
        val short = TextItem(
            id = ItemId(1), text = "hi", colorArgb = 0xFF112233.toInt(),
            sizeBoard = 24f, anchor = Pt(0f, 0f), wBoard = 800f, hBoard = 30f, eBoard = 42f,
        )
        val b = short.bounds()
        assertEquals(42f, b.right, 1e-4f)
        assertEquals(0f, b.left, 1e-4f)
    }

    @Test
    fun `a zero-extent text still yields a well-formed rect`() {
        val b = text(Pt(7f, 9f), 0f, 0f).bounds()
        assertEquals(0f, b.width, 1e-4f)
        assertEquals(0f, b.height, 1e-4f)
        assertEquals(7f, b.left, 1e-4f)
    }

    @Test
    fun `text participates in contentBounds like any other item`() {
        val doc = BoardDoc()
            .add(Stroke(ItemId(1), 0, 2f, listOf(Pt(0f, 0f))))
            .add(text(Pt(500f, 400f), 100f, 40f).copy(id = ItemId(2)))
        assertEquals(BoardRect(0f, 0f, 600f, 440f), doc.contentBounds())
    }

    @Test
    fun `the eraser does not affect text`() {
        // 规格 §6.4：橡皮只作用于「墨水类」。文字要删除得靠选择工具（阶段 1b-4）。
        val doc = BoardDoc().add(text(Pt(0f, 0f), 100f, 100f))
        assertEquals(emptyList<IndexedItem>(), HitTest.eraseHits(doc, Pt(50f, 50f), radius = 40f))
    }

    @Test
    fun `a stroke sitting under text is still erasable`() {
        // 反面：加入文字不应让橡皮对同位置的墨水失效
        val doc = BoardDoc()
            .add(Stroke(ItemId(1), 0, 2f, listOf(Pt(40f, 50f), Pt(60f, 50f))))
            .add(text(Pt(0f, 0f), 100f, 100f).copy(id = ItemId(2)))
        val hits = HitTest.eraseHits(doc, Pt(50f, 50f), radius = 20f)
        assertEquals(listOf(ItemId(1)), hits.map { it.item.id })
        assertFalse(hits.any { it.item is TextItem })
    }
}
