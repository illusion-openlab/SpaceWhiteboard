package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {

    @Test
    fun `toBoard and toScreen round trip`() {
        val v = Viewport(offsetX = 37f, offsetY = -12f, scale = 2.5f)
        val b = v.toBoard(400f, 300f)
        assertEquals(400f, v.toScreenX(b.x), 1e-3f)
        assertEquals(300f, v.toScreenY(b.y), 1e-3f)
    }

    @Test
    fun `identity viewport maps screen to board one to one`() {
        val v = Viewport()
        assertEquals(Pt(120f, 45f), v.toBoard(120f, 45f))
    }

    @Test
    fun `panBy shifts the offset in screen pixels`() {
        val v = Viewport(10f, 10f, 2f).panBy(5f, -3f)
        assertEquals(15f, v.offsetX, 1e-4f)
        assertEquals(7f, v.offsetY, 1e-4f)
        assertEquals(2f, v.scale, 1e-4f)
    }

    @Test
    fun `zoomAround keeps the anchor pointing at the same board coordinate`() {
        val v = Viewport(offsetX = 20f, offsetY = 40f, scale = 1f)
        val anchorX = 300f
        val anchorY = 200f
        val before = v.toBoard(anchorX, anchorY)
        val after = v.zoomAround(2f, anchorX, anchorY).toBoard(anchorX, anchorY)
        assertEquals(before.x, after.x, 1e-3f)
        assertEquals(before.y, after.y, 1e-3f)
    }

    @Test
    fun `zoom is clamped to MIN_SCALE and MAX_SCALE`() {
        assertEquals(Viewport.MAX_SCALE, Viewport(scale = 1f).zoomAround(100f, 0f, 0f).scale, 1e-4f)
        assertEquals(Viewport.MIN_SCALE, Viewport(scale = 1f).zoomAround(0.001f, 0f, 0f).scale, 1e-4f)
    }

    @Test
    fun `fit centers the whole logical canvas inside the view`() {
        // 视口 1200x800，画布 2400x1600 -> 缩放 0.5（恰为下限），左右上下都无留白
        val v = Viewport.fit(viewW = 1200f, viewH = 800f)
        assertEquals(0.5f, v.scale, 1e-4f)
        assertEquals(0f, v.offsetX, 1e-3f)
        assertEquals(0f, v.offsetY, 1e-3f)
    }

    @Test
    fun `fit letterboxes when the view aspect differs from the canvas`() {
        // 视口 2400x1600 的一半高：宽度受限于高度 -> scale = 800/1600 = 0.5，水平居中
        val v = Viewport.fit(viewW = 2400f, viewH = 800f)
        assertEquals(0.5f, v.scale, 1e-4f)
        assertEquals(600f, v.offsetX, 1e-3f)
        assertEquals(0f, v.offsetY, 1e-3f)
    }
}
