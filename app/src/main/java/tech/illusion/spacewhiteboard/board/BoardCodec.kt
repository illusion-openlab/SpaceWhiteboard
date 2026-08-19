package tech.illusion.spacewhiteboard.board

class CodecException(message: String) : Exception(message)

data class DecodeResult(val doc: BoardDoc, val skippedLines: Int)

/**
 * 行式文本编解码。
 *
 * 不用 JSON 的三条依据：
 *  1. 笔迹是成千上万个浮点数，JSON 的体积与解析开销都不划算；
 *  2. org.json 在 JVM 单测里是 stub（调用直接抛异常），而本类恰恰最需要被单测覆盖；
 *  3. 纯字符串处理零依赖、可流式、损坏时易于人工排查。
 */
object BoardCodec {

    const val HEADER = "wbd1"

    fun encode(doc: BoardDoc): String = buildString {
        append(HEADER).append('\n')
        for (item in doc.items) {
            when (item) {
                is Stroke -> {
                    append("s ").append(hex(item.colorArgb)).append(' ').append(item.widthDp)
                    for (p in item.points) append(' ').append(p.x).append(',').append(p.y)
                    append('\n')
                }
                is ShapeItem -> {
                    append("h ").append(item.kind.name)
                        .append(' ').append(hex(item.colorArgb))
                        .append(' ').append(item.widthDp)
                        .append(' ').append(item.a.x).append(',').append(item.a.y)
                        .append(' ').append(item.b.x).append(',').append(item.b.y)
                        .append('\n')
                }
            }
        }
    }

    fun decode(text: String): DecodeResult {
        val lines = text.split('\n')
        val headerLine = lines.firstOrNull { it.isNotBlank() }?.trim()
        if (headerLine != HEADER) {
            throw CodecException("bad or missing header: expected '$HEADER', got '$headerLine'")
        }

        val items = ArrayList<Item>()
        var skipped = 0
        var nextId = 1L
        var seenHeader = false

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!seenHeader) { seenHeader = true; continue }   // 跳过头行本身

            val parsed = try {
                parseLine(line, ItemId(nextId))
            } catch (e: Exception) {
                null   // 任何格式错误都降级为"跳过并计数"，绝不让一行坏数据毁掉整个文档
            }
            if (parsed == null) skipped++ else { items += parsed; nextId++ }
        }
        return DecodeResult(BoardDoc(items), skipped)
    }

    /** 返回 null 表示这一行无法识别或格式非法；抛异常同样由调用方降级为 skip。 */
    private fun parseLine(line: String, id: ItemId): Item? {
        val t = line.split(' ').filter { it.isNotEmpty() }
        return when (t.getOrNull(0)) {
            "s" -> {
                if (t.size < 4) return null                       // 至少要有一个坐标点
                val color = unhex(t[1])
                val width = t[2].toFloat()
                val pts = t.drop(3).map { parsePt(it) }
                Stroke(id, color, width, pts)
            }
            "h" -> {
                if (t.size != 6) return null
                val kind = ShapeKind.valueOf(t[1])
                ShapeItem(id, kind, unhex(t[2]), t[3].toFloat(), parsePt(t[4]), parsePt(t[5]))
            }
            else -> null                                          // 未知前缀（1b 的 t / i）
        }
    }

    private fun parsePt(token: String): Pt {
        val c = token.split(',')
        require(c.size == 2)
        return Pt(c[0].toFloat(), c[1].toFloat())
    }

    private fun hex(argb: Int): String = String.format("%08x", argb)

    private fun unhex(s: String): Int {
        require(s.length == 8 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
        return java.lang.Long.parseLong(s, 16).toInt()
    }
}
