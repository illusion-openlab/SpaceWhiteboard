package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCodecTextTest {

    private fun t(text: String) = TextItem(
        id = ItemId(1), text = text, colorArgb = 0xFF3366CC.toInt(),
        sizeBoard = 24.5f, anchor = Pt(120.25f, -8f), wBoard = 300f, hBoard = 72f,
    )

    @Test
    fun `a text item round trips every field`() {
        val out = BoardCodec.decode(BoardCodec.encode(BoardDoc().add(t("hello"))))
        assertEquals(0, out.skippedLines)
        val got = out.doc.items.single() as TextItem
        assertEquals("hello", got.text)
        assertEquals(0xFF3366CC.toInt(), got.colorArgb)
        assertEquals(24.5f, got.sizeBoard, 1e-6f)
        assertEquals(Pt(120.25f, -8f), got.anchor)
        assertEquals(300f, got.wBoard, 1e-6f)
        assertEquals(72f, got.hBoard, 1e-6f)
    }

    @Test
    fun `text containing spaces and newlines survives`() {
        // 这是 base64 存在的全部理由：这些字符会撕碎按空格分隔的行式格式
        val awkward = "第一行 有空格\n第二行\t有制表符"
        val out = BoardCodec.decode(BoardCodec.encode(BoardDoc().add(t(awkward))))
        assertEquals(0, out.skippedLines)
        assertEquals(awkward, (out.doc.items.single() as TextItem).text)
    }

    @Test
    fun `empty text round trips without becoming a skipped line`() {
        val out = BoardCodec.decode(BoardCodec.encode(BoardDoc().add(t(""))))
        assertEquals(0, out.skippedLines)
        assertEquals("", (out.doc.items.single() as TextItem).text)
    }

    @Test
    fun `text coexists with strokes and shapes in document order`() {
        val doc = BoardDoc()
            .add(Stroke(ItemId(1), 0xFF000000.toInt(), 2f, listOf(Pt(0f, 0f), Pt(5f, 5f))))
            .add(t("mid").copy(id = ItemId(2)))
            .add(ShapeItem(ItemId(3), ShapeKind.Rect, 0xFF00FF00.toInt(), 2f, Pt(0f, 0f), Pt(9f, 9f)))
        val out = BoardCodec.decode(BoardCodec.encode(doc))
        assertEquals(0, out.skippedLines)
        assertTrue(out.doc.items[0] is Stroke)
        assertTrue(out.doc.items[1] is TextItem)
        assertTrue(out.doc.items[2] is ShapeItem)
    }

    @Test
    fun `a malformed t line is skipped without harming its neighbours`() {
        val text = BoardCodec.HEADER + "\n" +
            "s ff000000 1.0 0.0,0.0 1.0,1.0\n" +
            "t ff000000 24.0 0.0,0.0 300.0\n" +          // 少一个字段
            "s ff000000 1.0 2.0,2.0 3.0,3.0\n"
        val out = BoardCodec.decode(text)
        assertEquals(1, out.skippedLines)
        assertEquals(2, out.doc.items.size)
    }

    @Test
    fun `an unknown prefix is still skipped rather than fatal`() {
        // 阶段 1b-3 会加 `i` 行；本版本必须容忍它，就像 1a 容忍 `t` 一样
        val text = BoardCodec.HEADER + "\n" +
            "i somehash.png 0.0,0.0 100.0 80.0\n" +
            "t ff000000 24.0 0.0,0.0 300.0 72.0 aGk=\n"
        val out = BoardCodec.decode(text)
        assertEquals(1, out.skippedLines)
        assertEquals(1, out.doc.items.size)
        assertEquals("hi", (out.doc.items.single() as TextItem).text)
    }
}
