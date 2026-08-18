package tech.illusion.spacewhiteboard.board

/**
 * 屏幕像素 ↔ 画布坐标的变换。
 *
 * 关键约定：所有 Item 的坐标一律存画布坐标，Viewport 只在渲染与输入转换时参与。
 * 因此缩放/平移不修改任何已存数据，导出 PNG 天然是画布原始分辨率。
 */
data class Viewport(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
) {
    fun toBoard(sx: Float, sy: Float): Pt = Pt((sx - offsetX) / scale, (sy - offsetY) / scale)

    fun toScreenX(bx: Float): Float = bx * scale + offsetX
    fun toScreenY(by: Float): Float = by * scale + offsetY

    fun panBy(dxPx: Float, dyPx: Float): Viewport = copy(offsetX = offsetX + dxPx, offsetY = offsetY + dyPx)

    /** 以屏幕上某点为锚点缩放：该点在缩放前后对应同一个画布坐标。 */
    fun zoomAround(factor: Float, anchorSx: Float, anchorSy: Float): Viewport {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val eff = newScale / scale
        return copy(
            scale = newScale,
            offsetX = anchorSx - (anchorSx - offsetX) * eff,
            offsetY = anchorSy - (anchorSy - offsetY) * eff,
        )
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 4.0f

        /** 画布逻辑尺寸（board units），固定不变。 */
        const val CANVAS_W = 2400f
        const val CANVAS_H = 1600f

        /** 让整张逻辑画布恰好装进给定视口并居中。 */
        fun fit(viewW: Float, viewH: Float): Viewport {
            val s = minOf(viewW / CANVAS_W, viewH / CANVAS_H).coerceIn(MIN_SCALE, MAX_SCALE)
            return Viewport(
                offsetX = (viewW - CANVAS_W * s) / 2f,
                offsetY = (viewH - CANVAS_H * s) / 2f,
                scale = s,
            )
        }
    }
}
