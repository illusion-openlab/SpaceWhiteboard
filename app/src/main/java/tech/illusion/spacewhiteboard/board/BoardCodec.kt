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
 *
 * `t` 行（文本）格式，8 个 token（1b-2 复审加入第 8 个 `eBoard`，`HEADER` 仍是 `wbd1`——
 * 旧的 7-token `t` 行按 token 数检查直接判定为畸形行，降级为跳过并计数，这正是格式设计好的
 * 向后兼容路径，不需要升版本号）：
 * ```
 * t <argb8位小写hex> <sizeBoard> <x>,<y> <wBoard> <hBoard> <eBoard> <base64(utf8 text)>
 * ```
 * `wBoard` 是换行约束（喂给测量的 `Constraints.maxWidth`），`eBoard` 是测量出的墨迹宽度
 * （`TextLayoutResult.size.width`）——两者不是同一个数，`eBoard` 才是 [TextItem.bounds] 用的
 * 那个宽度，详见 `Item.kt` 的 `TextItem` 类注释。
 *
 * 空文本的哨兵：base64 编码空字符串会产出空串，而 `decode` 按空格分词并过滤空 token，
 * 于是空文本的 `t` 行会少一个 token、被当成畸形行跳过——用户打开文字输入面板直接确认
 * 就能触发这条路径。为此，文本为空时最后一个 token 写成单个 `-` 而不是 base64 输出。
 * base64 字母表是 `A-Za-z0-9+/=`，永远不会产出裸 `-`，所以这个哨兵不会与真实编码冲突。
 */
object BoardCodec {

    private const val EMPTY_TEXT = "-"

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
                is TextItem -> {
                    append("t ").append(hex(item.colorArgb))
                        .append(' ').append(item.sizeBoard)
                        .append(' ').append(item.anchor.x).append(',').append(item.anchor.y)
                        .append(' ').append(item.wBoard)
                        .append(' ').append(item.hBoard)
                        .append(' ').append(item.eBoard)
                        .append(' ').append(encodeText(item.text))
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
            "t" -> {
                // 8 个 token；旧的 7-token 行（1b-2 复审加入 eBoard 之前）在这里直接判定为
                // 畸形行降级跳过——这就是 HEADER 不升版本号也能安全扩展格式的设计。
                if (t.size != 8) return null
                TextItem(
                    id = id,
                    text = decodeText(t[7]),
                    colorArgb = unhex(t[1]),
                    sizeBoard = t[2].toFloat(),
                    anchor = parsePt(t[3]),
                    wBoard = t[4].toFloat(),
                    hBoard = t[5].toFloat(),
                    eBoard = t[6].toFloat(),
                )
            }
            else -> null                                          // 未知前缀（1b 的 i）
        }
    }

    private fun parsePt(token: String): Pt {
        val c = token.split(',')
        require(c.size == 2)
        return Pt(c[0].toFloat(), c[1].toFloat())
    }

    private fun encodeText(text: String): String =
        if (text.isEmpty()) EMPTY_TEXT
        else java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun decodeText(token: String): String =
        if (token == EMPTY_TEXT) ""
        else String(java.util.Base64.getDecoder().decode(token), Charsets.UTF_8)

    private fun hex(argb: Int): String = String.format("%08x", argb)

    private fun unhex(s: String): Int {
        require(s.length == 8 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
        return java.lang.Long.parseLong(s, 16).toInt()
    }
}
