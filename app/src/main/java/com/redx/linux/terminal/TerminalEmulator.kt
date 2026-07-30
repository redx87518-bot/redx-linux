package com.redx.linux.terminal

/**
 * VT100/VT220 terminal emulator.
 * Maintains a 2D character buffer and processes ANSI escape sequences.
 */
class TerminalEmulator(var rows: Int, var cols: Int) {

    companion object {
        // ANSI 16 colors (0-7 normal, 8-15 bright)
        val ANSI_COLORS = intArrayOf(
            0xFF1A1A1A.toInt(), // 0 black
            0xFFCC0000.toInt(), // 1 red
            0xFF33FF33.toInt(), // 2 green
            0xFFFFCC00.toInt(), // 3 yellow
            0xFF3399FF.toInt(), // 4 blue
            0xFFCC33CC.toInt(), // 5 magenta
            0xFF33CCCC.toInt(), // 6 cyan
            0xFFCCCCCC.toInt(), // 7 white
            0xFF666666.toInt(), // 8 bright black
            0xFFFF5555.toInt(), // 9 bright red
            0xFF55FF55.toInt(), // 10 bright green
            0xFFFFFF55.toInt(), // 11 bright yellow
            0xFF5555FF.toInt(), // 12 bright blue
            0xFFFF55FF.toInt(), // 13 bright magenta
            0xFF55FFFF.toInt(), // 14 bright cyan
            0xFFFFFFFF.toInt()  // 15 bright white
        )
        const val DEFAULT_FG = 0xFF33FF33.toInt()  // classic green
        const val DEFAULT_BG = 0xFF000000.toInt()  // black
        const val CURSOR_COLOR = 0xFF33FF33.toInt()

        private const val ST_NORMAL = 0
        private const val ST_ESC = 1
        private const val ST_CSI = 2
        private const val ST_OSC = 3
    }

    data class Cell(
        var char: Char = ' ',
        var fg: Int = DEFAULT_FG,
        var bg: Int = DEFAULT_BG,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var blink: Boolean = false,
        var reverse: Boolean = false
    )

    var screen = Array(rows) { Array(cols) { Cell() } }
    var curRow = 0
    var curCol = 0
    var isDirty = true

    // Current attributes
    private var attrFg = DEFAULT_FG
    private var attrBg = DEFAULT_BG
    private var attrBold = false
    private var attrUnderline = false
    private var attrReverse = false

    // Scrolling region
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // State machine
    private var state = ST_NORMAL
    private val csiParams = StringBuilder()
    private val oscBuffer = StringBuilder()

    // Saved cursor
    private var savedRow = 0
    private var savedCol = 0

    // Scroll-back
    val scrollback = ArrayDeque<Array<Cell>>(1000)

    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        val newScreen = Array(newRows) { r ->
            Array(newCols) { c ->
                if (r < rows && c < cols) screen[r][c].copy() else Cell()
            }
        }
        rows = newRows
        cols = newCols
        screen = newScreen
        curRow = curRow.coerceIn(0, rows - 1)
        curCol = curCol.coerceIn(0, cols - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        isDirty = true
    }

    @Synchronized
    fun process(data: ByteArray, length: Int) {
        for (i in 0 until length) {
            processChar(data[i].toInt().and(0xFF).toChar())
        }
        isDirty = true
    }

    private fun processChar(c: Char) {
        when (state) {
            ST_NORMAL -> handleNormal(c)
            ST_ESC -> handleEsc(c)
            ST_CSI -> handleCsi(c)
            ST_OSC -> handleOsc(c)
        }
    }

    private fun handleNormal(c: Char) {
        when (c) {
            '\u001b' -> { state = ST_ESC }
            '\r' -> { curCol = 0 }
            '\n' -> { lineFeed() }
            '\b' -> { if (curCol > 0) curCol-- }
            '\t' -> {
                curCol = ((curCol / 8) + 1) * 8
                if (curCol >= cols) curCol = cols - 1
            }
            '\u0007' -> { /* bell - ignore */ }
            else -> {
                if (c >= ' ') {
                    putChar(c)
                }
            }
        }
    }

    private fun handleEsc(c: Char) {
        state = ST_NORMAL
        when (c) {
            '[' -> {
                csiParams.clear()
                state = ST_CSI
            }
            ']' -> {
                oscBuffer.clear()
                state = ST_OSC
            }
            'D' -> lineFeed()
            'M' -> reverseLineFeed()
            '7' -> { savedRow = curRow; savedCol = curCol }
            '8' -> { curRow = savedRow; curCol = savedCol }
            'c' -> reset()
            else -> { /* unhandled */ }
        }
    }

    private fun handleCsi(c: Char) {
        if (c.code in 0x30..0x3F) {
            // parameter bytes: 0-9, ;, ?, <, =, >
            csiParams.append(c)
            return
        }
        state = ST_NORMAL
        val params = csiParams.toString()
        executeCsi(c, params)
    }

    private fun handleOsc(c: Char) {
        when {
            c == '\u0007' -> { state = ST_NORMAL } // BEL terminates OSC
            c == '\u001b' -> { state = ST_ESC }    // will handle ST next
            else -> oscBuffer.append(c)
        }
    }

    private fun executeCsi(cmd: Char, raw: String) {
        val stripped = raw.trimStart('?', '<', '=', '>')
        val parts = if (stripped.isBlank()) listOf("") else stripped.split(";")
        fun p(i: Int, default: Int = 0) = parts.getOrNull(i)?.toIntOrNull() ?: default
        fun p1(i: Int) = p(i, 1)

        when (cmd) {
            'A' -> curRow = (curRow - p1(0)).coerceAtLeast(scrollTop)
            'B' -> curRow = (curRow + p1(0)).coerceAtMost(scrollBottom)
            'C' -> curCol = (curCol + p1(0)).coerceAtMost(cols - 1)
            'D' -> curCol = (curCol - p1(0)).coerceAtLeast(0)
            'E' -> { curRow = (curRow + p1(0)).coerceAtMost(rows - 1); curCol = 0 }
            'F' -> { curRow = (curRow - p1(0)).coerceAtLeast(0); curCol = 0 }
            'G' -> curCol = (p1(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                curRow = (p1(0) - 1).coerceIn(0, rows - 1)
                curCol = (p1(1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(p(0))
            'K' -> eraseLine(p(0))
            'L' -> insertLines(p1(0))
            'M' -> deleteLines(p1(0))
            'P' -> deleteChars(p1(0))
            'S' -> scrollUp(p1(0))
            'T' -> scrollDown(p1(0))
            '@' -> insertChars(p1(0))
            'd' -> curRow = (p1(0) - 1).coerceIn(0, rows - 1)
            'r' -> {
                scrollTop = (p1(0) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p1(1) - 1).coerceIn(0, rows - 1)
                if (scrollTop >= scrollBottom) { scrollTop = 0; scrollBottom = rows - 1 }
            }
            's' -> { savedRow = curRow; savedCol = curCol }
            'u' -> { curRow = savedRow; curCol = savedCol }
            'm' -> applySgr(parts)
            'h', 'l' -> { /* mode set/reset — ignore for now */ }
            'n' -> { /* device status — ignore */ }
            else -> { /* unhandled */ }
        }
    }

    private fun applySgr(parts: List<String>) {
        var i = 0
        while (i < parts.size) {
            val code = parts[i].toIntOrNull() ?: 0
            when (code) {
                0 -> {
                    attrFg = DEFAULT_FG; attrBg = DEFAULT_BG
                    attrBold = false; attrUnderline = false; attrReverse = false
                }
                1 -> attrBold = true
                4 -> attrUnderline = true
                7 -> attrReverse = true
                22 -> attrBold = false
                24 -> attrUnderline = false
                27 -> attrReverse = false
                in 30..37 -> attrFg = ANSI_COLORS[code - 30]
                38 -> {
                    if (parts.getOrNull(i + 1) == "5") {
                        val idx = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        attrFg = color256(idx)
                        i += 2
                    }
                }
                39 -> attrFg = DEFAULT_FG
                in 40..47 -> attrBg = ANSI_COLORS[code - 40]
                48 -> {
                    if (parts.getOrNull(i + 1) == "5") {
                        val idx = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        attrBg = color256(idx)
                        i += 2
                    }
                }
                49 -> attrBg = DEFAULT_BG
                in 90..97 -> attrFg = ANSI_COLORS[code - 90 + 8]
                in 100..107 -> attrBg = ANSI_COLORS[code - 100 + 8]
            }
            i++
        }
    }

    private fun color256(idx: Int): Int {
        return when {
            idx < 16 -> ANSI_COLORS[idx]
            idx < 232 -> {
                val n = idx - 16
                val b = (n % 6) * 51
                val g = ((n / 6) % 6) * 51
                val r = (n / 36) * 51
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> {
                val v = (idx - 232) * 10 + 8
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    private fun putChar(c: Char) {
        if (curCol >= cols) {
            curCol = 0
            lineFeed()
        }
        screen[curRow][curCol] = Cell(
            char = c,
            fg = if (attrReverse) attrBg else attrFg,
            bg = if (attrReverse) attrFg else attrBg,
            bold = attrBold,
            underline = attrUnderline
        )
        curCol++
    }

    private fun lineFeed() {
        if (curRow == scrollBottom) {
            scrollUp(1)
        } else {
            curRow = (curRow + 1).coerceAtMost(rows - 1)
        }
    }

    private fun reverseLineFeed() {
        if (curRow == scrollTop) {
            scrollDown(1)
        } else {
            curRow = (curRow - 1).coerceAtLeast(0)
        }
    }

    private fun scrollUp(n: Int) {
        repeat(n) {
            if (scrollback.size >= 1000) scrollback.removeFirst()
            scrollback.addLast(screen[scrollTop].clone())
            for (r in scrollTop until scrollBottom) {
                screen[r] = screen[r + 1].clone()
            }
            screen[scrollBottom] = Array(cols) { Cell() }
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (r in scrollBottom downTo scrollTop + 1) {
                screen[r] = screen[r - 1].clone()
            }
            screen[scrollTop] = Array(cols) { Cell() }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                for (c in curCol until cols) screen[curRow][c] = Cell()
                for (r in curRow + 1 until rows) screen[r] = Array(cols) { Cell() }
            }
            1 -> {
                for (r in 0 until curRow) screen[r] = Array(cols) { Cell() }
                for (c in 0..curCol) screen[curRow][c] = Cell()
            }
            2, 3 -> for (r in 0 until rows) screen[r] = Array(cols) { Cell() }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in curCol until cols) screen[curRow][c] = Cell()
            1 -> for (c in 0..curCol) screen[curRow][c] = Cell()
            2 -> screen[curRow] = Array(cols) { Cell() }
        }
    }

    private fun insertLines(n: Int) {
        val bottom = scrollBottom
        repeat(n) {
            for (r in bottom downTo curRow + 1) screen[r] = screen[r - 1].clone()
            screen[curRow] = Array(cols) { Cell() }
        }
    }

    private fun deleteLines(n: Int) {
        repeat(n) {
            for (r in curRow until scrollBottom) screen[r] = screen[r + 1].clone()
            screen[scrollBottom] = Array(cols) { Cell() }
        }
    }

    private fun deleteChars(n: Int) {
        val row = screen[curRow]
        for (c in curCol until cols) {
            row[c] = if (c + n < cols) row[c + n].copy() else Cell()
        }
    }

    private fun insertChars(n: Int) {
        val row = screen[curRow]
        for (c in cols - 1 downTo curCol + n) {
            row[c] = row[c - n].copy()
        }
        for (c in curCol until (curCol + n).coerceAtMost(cols)) {
            row[c] = Cell()
        }
    }

    private fun reset() {
        attrFg = DEFAULT_FG; attrBg = DEFAULT_BG
        attrBold = false; attrUnderline = false; attrReverse = false
        curRow = 0; curCol = 0
        scrollTop = 0; scrollBottom = rows - 1
        for (r in 0 until rows) screen[r] = Array(cols) { Cell() }
        state = ST_NORMAL
    }

    private fun Array<Cell>.clone() = Array(size) { this[it].copy() }
}
