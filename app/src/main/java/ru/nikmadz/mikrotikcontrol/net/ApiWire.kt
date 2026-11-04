package ru.nikmadz.mikrotikcontrol.net

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** RouterOS word framing. Bounds also protect against corrupt or endless peer responses. */
internal class ApiWire(private val input: InputStream, private val output: OutputStream,
                       private val beforeRead: () -> Unit = {}) {
    private var received = 0

    fun writeSentence(words: List<String>) {
        for (word in words) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MAX_WORD) throw IOException("API: неверная длина команды")
            writeLength(output, bytes.size.toLong())
            output.write(bytes)
        }
        output.write(0)
        output.flush()
    }

    fun readSentence(): List<String> {
        val words = mutableListOf<String>()
        while (true) {
            val size = readLength { byte() }
            if (size == 0L) return words
            if (size > MAX_WORD || words.size >= 128) throw IOException("API: превышен размер ответа")
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                beforeRead()
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw EOFException("API: соединение закрыто до полного ответа")
                received += count
                checkBudget()
                offset += count
            }
            words += try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { throw IOException("API: неверная кодировка ответа") }
        }
    }

    private fun byte(): Int {
        beforeRead()
        val value = input.read()
        if (value < 0) throw EOFException("API: соединение закрыто до подтверждения команды")
        received++
        checkBudget()
        return value
    }

    private fun checkBudget() {
        if (received > 8 * 1024 * 1024) throw IOException("API: превышен общий размер ответа")
    }

    companion object {
        private const val MAX_WORD = 1024 * 1024

        internal fun writeLength(output: OutputStream, value: Long) {
            require(value in 0..0xFFFFFFFFL)
            val encoded = when {
                value < 0x80 -> value
                value < 0x4000 -> value or 0x8000
                value < 0x200000 -> value or 0xC00000
                value < 0x10000000 -> value or 0xE0000000
                else -> value
            }
            val count = when {
                value < 0x80 -> 1
                value < 0x4000 -> 2
                value < 0x200000 -> 3
                value < 0x10000000 -> 4
                else -> { output.write(0xF0); 4 }
            }
            for (i in count - 1 downTo 0) output.write((encoded ushr (8 * i)).toInt() and 0xFF)
        }

        internal fun readLength(next: () -> Int): Long {
            val first = next()
            val count: Int
            var length: Long
            when {
                first < 0x80 -> return first.toLong()
                first < 0xC0 -> { count = 1; length = (first and 0x3F).toLong() }
                first < 0xE0 -> { count = 2; length = (first and 0x1F).toLong() }
                first < 0xF0 -> { count = 3; length = (first and 0x0F).toLong() }
                first == 0xF0 -> { count = 4; length = 0 }
                else -> throw IOException("API: неизвестный префикс длины")
            }
            repeat(count) { length = (length shl 8) or next().toLong() }
            return length
        }
    }
}
