package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoardCodecTest {

    private val doc = BoardDoc()
        .add(Stroke(ItemId(1), 0xFF3366CC.toInt(), 4.5f, listOf(Pt(1.5f, 2.25f), Pt(-3f, 8f))))
        .add(ShapeItem(ItemId(2), ShapeKind.Ellipse, 0xFFAA0011.toInt(), 2f, Pt(10f, 20f), Pt(30f, 5f)))

    @Test
    fun `encode then decode round trips every field`() {
        val out = BoardCodec.decode(BoardCodec.encode(doc))
        assertEquals(0, out.skippedLines)
        assertEquals(2, out.doc.items.size)

        val s = out.doc.items[0] as Stroke
        assertEquals(0xFF3366CC.toInt(), s.colorArgb)
        assertEquals(4.5f, s.widthDp, 1e-6f)
        assertEquals(listOf(Pt(1.5f, 2.25f), Pt(-3f, 8f)), s.points)

        val h = out.doc.items[1] as ShapeItem
        assertEquals(ShapeKind.Ellipse, h.kind)
        assertEquals(0xFFAA0011.toInt(), h.colorArgb)
        assertEquals(2f, h.widthDp, 1e-6f)
        assertEquals(Pt(10f, 20f), h.a)
        assertEquals(Pt(30f, 5f), h.b)
    }

    @Test
    fun `decode assigns sequential ids in document order`() {
        val out = BoardCodec.decode(BoardCodec.encode(doc))
        assertEquals(listOf(ItemId(1), ItemId(2)), out.doc.items.map { it.id })
    }

    @Test
    fun `empty document round trips`() {
        val out = BoardCodec.decode(BoardCodec.encode(BoardDoc()))
        assertTrue(out.doc.items.isEmpty())
        assertEquals(0, out.skippedLines)
    }

    @Test
    fun `missing header throws CodecException`() {
        try {
            BoardCodec.decode("s ff000000 1.0 0.0,0.0\n")
            fail("expected CodecException")
        } catch (e: CodecException) {
            assertTrue(e.message!!.contains("header"))
        }
    }

    @Test
    fun `wrong header version throws CodecException`() {
        try {
            BoardCodec.decode("wbd99\n")
            fail("expected CodecException")
        } catch (e: CodecException) {
            assertTrue(e.message!!.contains("header"))
        }
    }

    @Test
    fun `unknown line prefixes are skipped and counted, not fatal`() {
        // 阶段 1b 会追加 t / i 行；1a 的解码器必须容忍它们
        val text = BoardCodec.HEADER + "\n" +
            "t ff000000 24.0 5.0,5.0 300.0 SGVsbG8=\n" +
            "s ff000000 1.0 0.0,0.0\n"
        val out = BoardCodec.decode(text)
        assertEquals(1, out.skippedLines)
        assertEquals(1, out.doc.items.size)
    }

    @Test
    fun `malformed lines are skipped and counted`() {
        val text = BoardCodec.HEADER + "\n" +
            "s notahexcolor 1.0 0.0,0.0\n" +
            "h Rect ff000000 1.0 0.0,0.0\n" +          // 少一个点
            "s ff000000 1.0\n" +                        // 一个点都没有
            "s ff000000 1.0 4.0,4.0\n"                  // 唯一合法的一行
        val out = BoardCodec.decode(text)
        assertEquals(3, out.skippedLines)
        assertEquals(1, out.doc.items.size)
    }

    @Test
    fun `blank lines are ignored without counting as skipped`() {
        val text = BoardCodec.HEADER + "\n\n   \n" + "s ff000000 1.0 0.0,0.0\n"
        val out = BoardCodec.decode(text)
        assertEquals(0, out.skippedLines)
        assertEquals(1, out.doc.items.size)
    }

    // --- Extra guard tests (beyond the brief's 8) ---

    @Test
    fun `opaque colours round trip through the hex path`() {
        val d = BoardDoc()
            .add(Stroke(ItemId(1), 0xFF3366CC.toInt(), 1f, listOf(Pt(0f, 0f), Pt(1f, 1f))))
            .add(ShapeItem(ItemId(2), ShapeKind.Rect, 0x80123456.toInt(), 1f, Pt(0f, 0f), Pt(1f, 1f)))

        val out = BoardCodec.decode(BoardCodec.encode(d))
        assertEquals(0, out.skippedLines)

        val s = out.doc.items[0] as Stroke
        assertEquals(0xFF3366CC.toInt(), s.colorArgb)

        val h = out.doc.items[1] as ShapeItem
        assertEquals(0x80123456.toInt(), h.colorArgb)
    }

    @Test
    fun `multi-point stroke preserves point order`() {
        val points = listOf(
            Pt(-5.5f, 3f),
            Pt(0f, 0f),
            Pt(2.25f, -7.75f),
            Pt(100f, 100.5f),
            Pt(-0.001f, 42f),
        )
        val d = BoardDoc().add(Stroke(ItemId(1), 0xFF000000.toInt(), 1f, points))

        val out = BoardCodec.decode(BoardCodec.encode(d))
        assertEquals(0, out.skippedLines)
        val s = out.doc.items[0] as Stroke
        assertEquals(points, s.points)
    }

    @Test
    fun `a malformed line does not consume its neighbouring valid lines`() {
        val text = BoardCodec.HEADER + "\n" +
            "s ff000000 1.0 0.0,0.0\n" +
            "s notahexcolor 1.0 1.0,1.0\n" +
            "s ff000000 1.0 2.0,2.0\n"
        val out = BoardCodec.decode(text)
        assertEquals(1, out.skippedLines)
        assertEquals(2, out.doc.items.size)
        assertEquals(Pt(0.0f, 0.0f), (out.doc.items[0] as Stroke).points[0])
        assertEquals(Pt(2.0f, 2.0f), (out.doc.items[1] as Stroke).points[0])
    }
}
