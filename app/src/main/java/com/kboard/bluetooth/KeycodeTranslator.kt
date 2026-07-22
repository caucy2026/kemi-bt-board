package com.kboard.bluetooth

import android.icu.text.Transliterator

object KeycodeTranslator {

    private val transliterator = Transliterator.getInstance("Han-Latin; Latin-Ascii; Lower")

    // Returns Pair(Modifiers, ScanCode)
    // Modifiers: Bit 1 (0x02) = Left Shift
    fun getHidScanCode(char: Char): Pair<Byte, Byte>? {
        val shiftMod: Byte = 0x02
        return when (char) {
            in 'a'..'z' -> Pair(0, (0x04 + (char - 'a')).toByte())
            in 'A'..'Z' -> Pair(shiftMod, (0x04 + (char - 'A')).toByte())
            in '1'..'9' -> Pair(0, (0x1E + (char - '1')).toByte())
            '0' -> Pair(0, 0x27.toByte())
            
            // Special symbols and control keys
            ' ' -> Pair(0, 0x2C.toByte())
            '\n' -> Pair(0, 0x28.toByte()) // Enter
            '\t' -> Pair(0, 0x2B.toByte()) // Tab
            '\b' -> Pair(0, 0x2A.toByte()) // Backspace
            
            '-' -> Pair(0, 0x2D.toByte())
            '_' -> Pair(shiftMod, 0x2D.toByte())
            '=' -> Pair(0, 0x2E.toByte())
            '+' -> Pair(shiftMod, 0x2E.toByte())
            
            '[' -> Pair(0, 0x2F.toByte())
            '{' -> Pair(shiftMod, 0x2F.toByte())
            ']' -> Pair(0, 0x30.toByte())
            '}' -> Pair(shiftMod, 0x30.toByte())
            
            '\\' -> Pair(0, 0x31.toByte())
            '|' -> Pair(shiftMod, 0x31.toByte())
            
            ';' -> Pair(0, 0x33.toByte())
            ':' -> Pair(shiftMod, 0x33.toByte())
            '\'' -> Pair(0, 0x34.toByte())
            '"' -> Pair(shiftMod, 0x34.toByte())
            
            '`' -> Pair(0, 0x35.toByte())
            '~' -> Pair(shiftMod, 0x35.toByte())
            
            ',' -> Pair(0, 0x36.toByte())
            '<' -> Pair(shiftMod, 0x36.toByte())
            '.' -> Pair(0, 0x37.toByte())
            '>' -> Pair(shiftMod, 0x37.toByte())
            '/' -> Pair(0, 0x38.toByte())
            '?' -> Pair(shiftMod, 0x38.toByte())
            
            '!' -> Pair(shiftMod, 0x1E.toByte()) // Shift + 1
            '@' -> Pair(shiftMod, 0x1F.toByte()) // Shift + 2
            '#' -> Pair(shiftMod, 0x20.toByte()) // Shift + 3
            '$' -> Pair(shiftMod, 0x21.toByte()) // Shift + 4
            '%' -> Pair(shiftMod, 0x22.toByte()) // Shift + 5
            '^' -> Pair(shiftMod, 0x23.toByte()) // Shift + 6
            '&' -> Pair(shiftMod, 0x24.toByte()) // Shift + 7
            '*' -> Pair(shiftMod, 0x25.toByte()) // Shift + 8
            '(' -> Pair(shiftMod, 0x26.toByte()) // Shift + 9
            ')' -> Pair(shiftMod, 0x27.toByte()) // Shift + 0
            
            else -> null
        }
    }

    // Translates input text (potentially mixed Chinese and English) into keystroke characters.
    // Chinese characters are translated to continuous Pinyin with syllable separators (apostrophe) and committed with a trailing space.
    // English/ASCII characters are kept as is.
    @Synchronized
    fun translateText(text: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = text.length
        while (i < len) {
            val char = text[i]
            if (char.code in 0..127) {
                // Collect continuous ASCII / English chunk
                val start = i
                while (i < len && text[i].code in 0..127) {
                    i++
                }
                val asciiChunk = text.substring(start, i)
                sb.append(formatAsciiChunkForPinyinMode(asciiChunk))
            } else {
                // Chinese character or Chinese punctuation
                val pinyin = transliterator.transliterate(char.toString())
                if (pinyin.isNotEmpty() && pinyin[0] in 'a'..'z') {
                    sb.append(pinyin).append(' ')
                } else {
                    val asciiPunct = mapChinesePunctuationToAscii(char)
                    sb.append(asciiPunct)
                }
                i++
            }
        }
        return sb.toString()
    }

    private fun formatAsciiChunkForPinyinMode(chunk: String): String {
        val sb = StringBuilder()
        var word = StringBuilder()
        for (ch in chunk) {
            if (ch.isLetter()) {
                word.append(ch)
            } else {
                if (word.isNotEmpty()) {
                    sb.append(word).append('\n') // Press Enter (\n) to force English word commit in Pinyin IME
                    word.clear()
                }
                sb.append(ch)
            }
        }
        if (word.isNotEmpty()) {
            sb.append(word).append('\n')
        }
        return sb.toString()
    }

    private fun mapChinesePunctuationToAscii(char: Char): String {
        return when (char) {
            '，' -> ", "
            '。' -> ". "
            '！' -> "! "
            '？' -> "? "
            '：' -> ": "
            '；' -> "; "
            '“', '”' -> "\""
            '‘', '’' -> "'"
            '（' -> "("
            '）' -> ")"
            '【' -> "["
            '】' -> "]"
            '—' -> "-"
            '…' -> "..."
            else -> " "
        }
    }
}
