package com.davidlang.torque.fuelsmoothing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Hand-written POSIX ustar. The prefix field carries paths up to 255 bytes.
 * The reader creates only regular files and directories under [root].
 */
internal object DataTransferTar {
    private const val BLOCK = 512

    internal data class Header(
        val path: String,
        val size: Long,
        val type: Char,
        val linkName: String,
    )

    internal data class RawEntry(
        val path: String,
        val type: Char,
        val linkName: String,
        val data: ByteArray,
    )

    fun writeFile(out: OutputStream, path: String, data: ByteArray, mtimeSec: Long = 0L) {
        writeHeader(out, path, data.size.toLong(), '0', "", mtimeSec)
        out.write(data)
        writePad(out, data.size.toLong())
    }

    fun writeFileFrom(out: OutputStream, path: String, file: File, mtimeSec: Long = 0L) {
        val size = file.length()
        writeHeader(out, path, size, '0', "", mtimeSec)
        file.inputStream().use { it.copyTo(out) }
        writePad(out, size)
    }

    fun writeDir(out: OutputStream, path: String, mtimeSec: Long = 0L) {
        writeHeader(out, path.trimEnd('/'), 0L, '5', "", mtimeSec)
    }

    fun writeSpecial(out: OutputStream, path: String, type: Char, linkName: String = "") {
        writeHeader(out, path, 0L, type, linkName, 0L)
    }

    fun writeEnd(out: OutputStream) {
        out.write(ByteArray(BLOCK))
        out.write(ByteArray(BLOCK))
    }

    fun writeHeader(out: OutputStream, path: String, size: Long, type: Char, linkName: String = "", mtimeSec: Long = 0L) {
        val (name, prefix) = splitPath(path)
        if (linkName.toByteArray(Charsets.UTF_8).size > 100) {
            throw IllegalArgumentException("datatransfer: link name longer than 100 bytes")
        }
        val buf = ByteArray(BLOCK)
        putBytes(buf, 0, 100, name)
        putOctal(buf, 100, 8, if (type == '5') 0x1EDL else 0x1A4L)
        putOctal(buf, 108, 8, 0L)
        putOctal(buf, 116, 8, 0L)
        putOctal(buf, 124, 12, size)
        putOctal(buf, 136, 12, mtimeSec)
        buf[156] = type.code.toByte()
        putBytes(buf, 157, 100, linkName)
        putBytes(buf, 257, 6, "ustar")
        buf[262] = 0
        buf[263] = '0'.code.toByte()
        buf[264] = '0'.code.toByte()
        putBytes(buf, 345, 155, prefix)
        val sum = checksum(buf)
        val digits = sum.toString(8).padStart(6, '0')
        if (digits.length != 6) throw IllegalArgumentException("datatransfer: checksum does not fit")
        for (i in digits.indices) buf[148 + i] = digits[i].code.toByte()
        buf[154] = 0
        buf[155] = ' '.code.toByte()
        out.write(buf)
    }

    fun readRaw(input: InputStream): List<RawEntry> {
        val out = ArrayList<RawEntry>()
        process(input) { h, body ->
            val data = readExactBytes(body, h.size)
            out.add(RawEntry(h.path, h.type, h.linkName, data))
        }
        return out
    }

    /** Materializes regular files and directories. A confined-path failure throws before that entry is created. */
    fun extract(input: InputStream, root: File): List<String> {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("datatransfer: cannot create ${root.path}")
        val names = ArrayList<String>()
        process(input) { h, body ->
            names.add(h.path)
            materialize(root, h, body)
        }
        return names
    }

    private fun materialize(root: File, h: Header, body: InputStream) {
        when (h.type) {
            '0', '\u0000', '7' -> { /* file */ }
            '5' -> { /* dir */ }
            '1' -> throw IllegalArgumentException("datatransfer: hardlink entry refused: ${h.path}")
            '2' -> throw IllegalArgumentException("datatransfer: symlink entry refused: ${h.path}")
            '3', '4' -> throw IllegalArgumentException("datatransfer: device entry refused: ${h.path}")
            else -> throw IllegalArgumentException("datatransfer: unsupported tar type '${h.type}': ${h.path}")
        }
        val rel = normalizeRelative(h.path)
        val target = File(root, rel)
        ensureInside(root, target)
        if (h.type == '5') {
            if (h.size != 0L) throw IllegalArgumentException("datatransfer: directory has a body: $rel")
            if (target.isFile) throw IOException("datatransfer: directory path is a file: ${target.path}")
            if (!target.isDirectory && !target.mkdirs()) throw IOException("datatransfer: cannot mkdir ${target.path}")
            return
        }
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("datatransfer: cannot mkdir ${parent.path}")
        }
        FileOutputStream(target).use { out -> copyExact(body, out, h.size) }
    }

    private fun process(input: InputStream, block: (Header, InputStream) -> Unit) {
        while (true) {
            val h = nextHeader(input) ?: return
            val limited = LimitedInputStream(input, h.size)
            block(h, limited)
            limited.drain()
            skip(input, padLen(h.size))
        }
    }

    private fun nextHeader(input: InputStream): Header? {
        val buf = readExact(input, BLOCK) ?: throw IOException("datatransfer: truncated tar")
        if (buf.all { it == 0.toByte() }) {
            // One zero block ends the archive. A second block, when present, is padding.
            return null
        }
        val stored = parseOctal(buf, 148, 8)
        val sum = checksum(buf)
        if (stored != sum.toLong()) throw IOException("datatransfer: bad ustar checksum")
        val magic = cString(buf, 257, 6)
        if (!magic.startsWith("ustar")) throw IOException("datatransfer: not a ustar entry")
        val name = cString(buf, 0, 100)
        val prefix = cString(buf, 345, 155)
        if (name.isEmpty()) throw IOException("datatransfer: ustar entry has an empty name")
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        val type = buf[156].toInt().toChar()
        val size = parseOctal(buf, 124, 12)
        if (size < 0L) throw IOException("datatransfer: negative ustar size")
        val link = cString(buf, 157, 100)
        return Header(path, size, type, link)
    }

    internal fun normalizeRelative(path: String): String {
        if (path.startsWith("/")) throw IllegalArgumentException("datatransfer: absolute path refused: $path")
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") throw IllegalArgumentException("datatransfer: path escapes with ..: $path")
            parts.add(seg)
        }
        if (parts.isEmpty()) throw IllegalArgumentException("datatransfer: empty path")
        return parts.joinToString("/")
    }

    private fun ensureInside(root: File, target: File) {
        val rootPath = root.absoluteFile.path.trimEnd(File.separatorChar)
        val targetPath = target.absoluteFile.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("datatransfer: path escapes root: $targetPath")
        }
    }

    internal fun splitPath(path: String): Pair<String, String> {
        val raw = path.trimEnd('/')
        if (raw.isEmpty()) throw IllegalArgumentException("datatransfer: empty path")
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size > 255) throw IllegalArgumentException("datatransfer: path longer than 255 bytes: $raw")
        if (bytes.size <= 100) return raw to ""
        var cut = -1
        for (i in raw.indices) {
            if (raw[i] != '/') continue
            val prefix = raw.substring(0, i)
            val name = raw.substring(i + 1)
            if (prefix.isNotEmpty() && name.isNotEmpty() &&
                prefix.toByteArray(Charsets.UTF_8).size <= 155 &&
                name.toByteArray(Charsets.UTF_8).size <= 100
            ) {
                cut = i
            }
        }
        if (cut < 0) throw IllegalArgumentException("datatransfer: path cannot fit ustar name and prefix: $raw")
        return raw.substring(cut + 1) to raw.substring(0, cut)
    }

    private fun checksum(buf: ByteArray): Int {
        var sum = 0
        for (i in buf.indices) {
            sum += if (i in 148 until 156) 0x20 else buf[i].toInt() and 0xff
        }
        return sum
    }

    private fun putBytes(buf: ByteArray, off: Int, len: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size > len) throw IllegalArgumentException("datatransfer: ustar field longer than $len bytes")
        b.copyInto(buf, off)
    }

    private fun putOctal(buf: ByteArray, off: Int, len: Int, value: Long) {
        if (value < 0L) throw IllegalArgumentException("datatransfer: negative ustar field")
        val digits = value.toString(8).padStart(len - 1, '0')
        if (digits.length != len - 1) throw IllegalArgumentException("datatransfer: octal field does not fit")
        for (i in digits.indices) buf[off + i] = digits[i].code.toByte()
        buf[off + len - 1] = 0
    }

    private fun parseOctal(buf: ByteArray, off: Int, len: Int): Long {
        val s = cString(buf, off, len).trim()
        if (s.isEmpty()) return 0L
        return s.toLong(8)
    }

    private fun cString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && buf[end] != 0.toByte()) end++
        return buf.copyOfRange(off, end).toString(Charsets.UTF_8)
    }

    private fun padLen(size: Long): Long {
        val rem = size % BLOCK
        return if (rem == 0L) 0L else BLOCK - rem
    }

    private fun writePad(out: OutputStream, size: Long) {
        val n = padLen(size)
        if (n > 0L) out.write(ByteArray(n.toInt()))
    }

    /** Null only when the stream is already at EOF at the start of a header after at least one byte of a previous read failed immediately... */
    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) {
                if (off == 0) return null
                throw IOException("datatransfer: truncated tar")
            }
            off += r
        }
        return buf
    }

    private fun readExactBytes(input: InputStream, size: Long): ByteArray {
        if (size > Int.MAX_VALUE) throw IOException("datatransfer: entry too large")
        val out = ByteArrayOutputStream(size.toInt())
        copyExact(input, out, size)
        return out.toByteArray()
    }

    private fun copyExact(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(8192)
        var left = size
        while (left > 0L) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("datatransfer: truncated tar entry")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, n: Long) {
        val buf = ByteArray(8192)
        var left = n
        while (left > 0L) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("datatransfer: truncated tar")
            left -= r
        }
    }

    private class LimitedInputStream(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0L) return -1
            val v = src.read()
            if (v >= 0) left--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0L) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        fun drain() {
            val buf = ByteArray(8192)
            while (left > 0L) {
                val n = read(buf)
                if (n < 0) throw IOException("datatransfer: truncated tar entry")
            }
        }
    }
}
