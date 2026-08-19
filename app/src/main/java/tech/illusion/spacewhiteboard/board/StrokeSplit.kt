package tech.illusion.spacewhiteboard.board

/**
 * 橡皮的矢量切分。
 *
 * 为什么不做位图擦除：那会毁掉矢量特性 —— 缩放清晰度、导出分辨率、笔迹可再编辑性全部失去，
 * 而这个应用的整个文档模型都是矢量对象。点列切分保留全部这些性质，且是纯几何、可单测。
 */
object StrokeSplit {

    /** 长度小于这个值的存活段会被丢弃：一两个孤点在视觉上是擦除碎屑，不是用户想留的笔迹。 */
    private const val MIN_RUN = 2

    /**
     * 剔除所有到 [center] 距离 <= [radius] 的点，返回剩余点按原顺序构成的连续段。
     * 边界是闭区间（恰好等于半径的点会被擦掉）—— 擦除倾向于多擦一点点，
     * 因为「擦不干净」比「多擦一丁点」更让人烦躁。
     */
    fun split(points: List<Pt>, center: Pt, radius: Float): List<List<Pt>> {
        if (points.isEmpty()) return emptyList()
        val runs = ArrayList<List<Pt>>()
        var cur = ArrayList<Pt>()
        for (p in points) {
            if (p.distanceTo(center) <= radius) {
                if (cur.size >= MIN_RUN) runs.add(cur)
                cur = ArrayList()
            } else {
                cur.add(p)
            }
        }
        if (cur.size >= MIN_RUN) runs.add(cur)
        return runs
    }
}
