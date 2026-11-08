package io.codemyriad.cassini.engine.whistle

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Resolve a stored ZIP member without extracting or loading the weight container as code. ZIP64 is refused. */
internal object ApkStoredEntry {
    fun offset(file: File, name: String, size: Long): Long = RandomAccessFile(file, "r").use { input ->
        val tailSize = minOf(input.length(), MAX_TAIL.toLong()).toInt()
        val tail = ByteArray(tailSize)
        input.seek(input.length() - tailSize)
        input.readFully(tail)
        val end = (tailSize - END_SIZE downTo 0).firstOrNull {
            int(tail, it) == END_SIGNATURE && it + END_SIZE + short(tail, it + COMMENT_LENGTH) == tailSize
        } ?: error("APK ZIP directory missing")
        check(short(tail, end + DISK) == 0 && short(tail, end + DIRECTORY_DISK) == 0)
        val entries = short(tail, end + ENTRY_COUNT)
        check(entries != ZIP64_COUNT)
        val directory = uint(tail, end + DIRECTORY_OFFSET)
        input.seek(directory)
        repeat(entries) {
            val header = ByteArray(CENTRAL_SIZE)
            input.readFully(header)
            check(int(header, 0) == CENTRAL_SIGNATURE)
            val nameSize = short(header, CENTRAL_NAME)
            val memberName = ByteArray(nameSize)
            input.readFully(memberName)
            if (memberName.toString(Charsets.UTF_8) == name) {
                check(short(header, CENTRAL_METHOD) == 0 && short(header, CENTRAL_FLAGS) and 1 == 0)
                check(uint(header, CENTRAL_UNCOMPRESSED) == size && uint(header, CENTRAL_COMPRESSED) == size)
                input.seek(uint(header, LOCAL_OFFSET))
                val local = ByteArray(LOCAL_SIZE)
                input.readFully(local)
                check(int(local, 0) == LOCAL_SIGNATURE && short(local, LOCAL_METHOD) == 0)
                val localName = ByteArray(short(local, LOCAL_NAME))
                input.readFully(localName)
                check(localName.contentEquals(memberName))
                val offset = input.filePointer + short(local, LOCAL_EXTRA)
                check(offset >= 0 && offset + size <= directory)
                return@use offset
            }
            input.skipBytes(short(header, CENTRAL_EXTRA) + short(header, CENTRAL_COMMENT))
        }
        error("Stored APK member missing")
    }

    private fun int(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).int
    private fun uint(bytes: ByteArray, offset: Int) = int(bytes, offset).toLong() and UINT_MASK
    private fun short(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, Short.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and USHORT_MASK

    private const val MAX_TAIL = 65557
    private const val END_SIZE = 22
    private const val END_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val COMMENT_LENGTH = 20
    private const val DISK = 4
    private const val DIRECTORY_DISK = 6
    private const val ENTRY_COUNT = 10
    private const val DIRECTORY_OFFSET = 16
    private const val ZIP64_COUNT = 65535
    private const val CENTRAL_SIZE = 46
    private const val CENTRAL_FLAGS = 8
    private const val CENTRAL_METHOD = 10
    private const val CENTRAL_COMPRESSED = 20
    private const val CENTRAL_UNCOMPRESSED = 24
    private const val CENTRAL_NAME = 28
    private const val CENTRAL_EXTRA = 30
    private const val CENTRAL_COMMENT = 32
    private const val LOCAL_OFFSET = 42
    private const val LOCAL_SIZE = 30
    private const val LOCAL_METHOD = 8
    private const val LOCAL_NAME = 26
    private const val LOCAL_EXTRA = 28
    private const val UINT_MASK = 0xffffffffL
    private const val USHORT_MASK = 0xffff
}
