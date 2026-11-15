package com.mindtravel.deepseekassistant.platform.update

import com.mindtravel.deepseekassistant.core.ApiException
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

object BsPatch {

    private const val MAGIC = "BSDIFF40"
    private const val MAX_NEW_SIZE = 120L * 1024 * 1024

    fun apply(oldApk: File, patch: File, output: File) {
        if (!oldApk.isFile) throw ApiException("找不到当前安装包")
        FileInputStream(patch).use { input ->
            val header = ByteArray(32)
            readFully(input, header)
            if (String(header, 0, 8, Charsets.US_ASCII) != MAGIC) {
                throw ApiException("补丁格式错误")
            }
            val ctrlLen = readLong(header, 8)
            val diffLen = readLong(header, 16)
            val newSize = readLong(header, 24)
            if (ctrlLen <= 0 || diffLen < 0 || newSize <= 0 || newSize > MAX_NEW_SIZE) {
                throw ApiException("补丁头异常")
            }
            val ctrlCompressed = readExact(input, ctrlLen.toInt())
            val diffCompressed = readExact(input, diffLen.toInt())
            val extraCompressed = input.readBytes()
            val ctrl = decompress(ctrlCompressed)
            val diff = decompress(diffCompressed)
            val extra = decompress(extraCompressed)
            if (ctrl.size < 24) throw ApiException("补丁控制块异常")

            RandomAccessFile(oldApk, "r").use { old ->
                FileOutputStream(output).use { out ->
                    var oldPos = 0L
                    var newPos = 0L
                    var ctrlPos = 0
                    var diffPos = 0
                    var extraPos = 0
                    val buffer = ByteArray(64 * 1024)
                    while (newPos < newSize) {
                        if (ctrlPos + 24 > ctrl.size) throw ApiException("补丁控制块不足")
                        val add = readLong(ctrl, ctrlPos)
                        val copy = readLong(ctrl, ctrlPos + 8)
                        val seek = readLong(ctrl, ctrlPos + 16)
                        ctrlPos += 24
                        if (add < 0 || copy < 0 || newPos + add > newSize) {
                            throw ApiException("补丁控制数据异常")
                        }
                        if (diffPos + add > diff.size) throw ApiException("补丁差异块不足")
                        old.seek(oldPos)
                        var remaining = add
                        var diffAt = diffPos
                        while (remaining > 0) {
                            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                            old.readFully(buffer, 0, chunk)
                            for (i in 0 until chunk) {
                                buffer[i] = (buffer[i] + diff[diffAt + i]).toByte()
                            }
                            out.write(buffer, 0, chunk)
                            remaining -= chunk
                            diffAt += chunk
                        }
                        newPos += add
                        diffPos += add.toInt()
                        if (copy > 0) {
                            if (extraPos + copy > extra.size) throw ApiException("补丁额外块不足")
                            out.write(extra, extraPos, copy.toInt())
                            extraPos += copy.toInt()
                            newPos += copy
                        }
                        oldPos += add + seek
                        if (oldPos < 0) throw ApiException("补丁跳转越界")
                    }
                    if (newPos != newSize) throw ApiException("补丁输出大小异常")
                }
            }
        }
    }

    private fun decompress(bytes: ByteArray): ByteArray =
        BZip2CompressorInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0..6) {
            value = value or ((bytes[offset + i].toLong() and 0xffL) shl (8 * i))
        }
        value = value or ((bytes[offset + 7].toLong() and 0x7fL) shl 56)
        return if (bytes[offset + 7].toInt() and 0x80 != 0) -value else value
    }

    private fun readFully(input: FileInputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw ApiException("补丁文件不完整")
            offset += read
        }
    }

    private fun readExact(input: FileInputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        readFully(input, bytes)
        return bytes
    }
}
