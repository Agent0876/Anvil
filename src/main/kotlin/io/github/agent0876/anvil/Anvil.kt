package io.github.agent0876.anvil

import net.jpountz.lz4.LZ4BlockInputStream
import net.jpountz.lz4.LZ4BlockOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

class Anvil(val path: Path) : Closeable {

    private val regions = HashMap<Long, RegionFile>()

    init {
        Files.createDirectories(path)
    }

    @Synchronized
    fun readChunk(chunkX: Int, chunkZ: Int): ByteArray? =
        getOrOpenRegion(chunkX, chunkZ).readChunk(chunkX and 31, chunkZ and 31)

    @Synchronized
    fun writeChunk(chunkX: Int, chunkZ: Int, data: ByteArray) =
        getOrOpenRegion(chunkX, chunkZ).writeChunk(chunkX and 31, chunkZ and 31, data)

    @Synchronized
    fun hasChunk(chunkX: Int, chunkZ: Int): Boolean =
        getOrOpenRegion(chunkX, chunkZ).hasChunk(chunkX and 31, chunkZ and 31)

    @Synchronized
    fun deleteChunk(chunkX: Int, chunkZ: Int) =
        getOrOpenRegion(chunkX, chunkZ).deleteChunk(chunkX and 31, chunkZ and 31)

    @Synchronized
    override fun close() {
        regions.values.forEach(RegionFile::close)
        regions.clear()
    }

    private fun getOrOpenRegion(chunkX: Int, chunkZ: Int): RegionFile {
        val regionX = chunkX shr 5
        val regionZ = chunkZ shr 5
        val key = regionX.toLong() shl 32 or (regionZ.toLong() and 0xFFFFFFFFL)
        return regions.getOrPut(key) { RegionFile(path.resolve("r.$regionX.$regionZ.mca"), regionX, regionZ) }
    }

    private class RegionFile(
        private val filePath: Path,
        private val regionX: Int,
        private val regionZ: Int
    ) : Closeable {

        companion object {
            private const val SECTOR_BYTES = 4096
            private const val SECTOR_INTS = 1024
            private const val EXTERNAL_CHUNK_THRESHOLD = 256
            private const val EXTERNAL_FLAG = 0x80

            private const val VERSION_GZIP: Byte = 1
            private const val VERSION_DEFLATE: Byte = 2
            private const val VERSION_NONE: Byte = 3
            private const val VERSION_LZ4: Byte = 4
        }

        private val externalDir: Path = filePath.parent
        private val header = ByteBuffer.allocateDirect(SECTOR_BYTES * 2)
        private val offsets: IntBuffer
        private val timestamps: IntBuffer
        private val usedSectors = BitSet()
        private val file: FileChannel

        init {
            offsets = header.asIntBuffer().also { it.limit(SECTOR_INTS) }
            header.position(SECTOR_BYTES)
            timestamps = header.asIntBuffer()
            header.position(0)

            file = FileChannel.open(filePath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)

            usedSectors.set(0, 2)

            val readBytes = file.read(header, 0L)
            if (readBytes > 0) {
                val fileSize = file.size()
                for (i in 0 until SECTOR_INTS) {
                    val offset = offsets.get(i)
                    if (offset != 0) {
                        val sectorNum = sectorNumber(offset)
                        val count = numSectors(offset)
                        if (sectorNum >= 2 && count > 0 && sectorNum * SECTOR_BYTES.toLong() <= fileSize) {
                            usedSectors.set(sectorNum, sectorNum + count)
                        } else {
                            offsets.put(i, 0)
                        }
                    }
                }
            }
        }

        fun hasChunk(localX: Int, localZ: Int): Boolean =
            offsets.get(localX + localZ * 32) != 0

        fun readChunk(localX: Int, localZ: Int): ByteArray? {
            val offset = offsets.get(localX + localZ * 32)
            if (offset == 0) return null

            val sectorNum = sectorNumber(offset)
            val count = numSectors(offset)

            val buffer = ByteBuffer.allocate(count * SECTOR_BYTES)
            file.read(buffer, sectorNum * SECTOR_BYTES.toLong())
            buffer.flip()

            if (buffer.remaining() < 5) return null

            val length = buffer.getInt()
            val versionId = buffer.get()
            val streamLength = length - 1

            if (length == 0 || streamLength < 0) return null

            if (versionId.toInt() and EXTERNAL_FLAG != 0) {
                return readExternalChunk(localX, localZ, (versionId.toInt() and 0x7F).toByte())
            }

            if (streamLength > buffer.remaining()) return null

            val compressed = ByteArray(streamLength).also { buffer.get(it) }
            return decompress(versionId, compressed)
        }

        fun writeChunk(localX: Int, localZ: Int, data: ByteArray) {
            val compressed = compress(data)

            val idx = localX + localZ * 32
            val existingOffset = offsets.get(idx)
            val existingSector = sectorNumber(existingOffset)
            val existingCount = numSectors(existingOffset)

            val rawSectorsNeeded = (5 + compressed.size + SECTOR_BYTES - 1) / SECTOR_BYTES

            val newSector: Int
            val allocatedSectors: Int

            if (rawSectorsNeeded >= EXTERNAL_CHUNK_THRESHOLD) {
                writeExternalChunk(localX, localZ, compressed)
                allocatedSectors = 1
                newSector = allocateSectors(1)
                val stub = ByteBuffer.allocate(5).apply {
                    putInt(1)
                    put((VERSION_DEFLATE.toInt() or EXTERNAL_FLAG).toByte())
                    flip()
                }
                file.write(stub, newSector * SECTOR_BYTES.toLong())
            } else {
                Files.deleteIfExists(externalChunkPath(localX, localZ))
                allocatedSectors = rawSectorsNeeded
                newSector = allocateSectors(allocatedSectors)
                val payload = ByteBuffer.allocate(5 + compressed.size).apply {
                    putInt(compressed.size + 1)
                    put(VERSION_DEFLATE)
                    put(compressed)
                    flip()
                }
                file.write(payload, newSector * SECTOR_BYTES.toLong())
            }

            offsets.put(idx, packOffset(newSector, allocatedSectors))
            timestamps.put(idx, (System.currentTimeMillis() / 1000L).toInt())
            writeHeader()

            if (existingSector != 0) {
                usedSectors.clear(existingSector, existingSector + existingCount)
            }
        }

        fun deleteChunk(localX: Int, localZ: Int) {
            val idx = localX + localZ * 32
            val offset = offsets.get(idx)
            if (offset == 0) return

            offsets.put(idx, 0)
            timestamps.put(idx, (System.currentTimeMillis() / 1000L).toInt())
            writeHeader()

            Files.deleteIfExists(externalChunkPath(localX, localZ))
            usedSectors.clear(sectorNumber(offset), sectorNumber(offset) + numSectors(offset))
        }

        override fun close() {
            try {
                padToSectorBoundary()
            } finally {
                try {
                    file.force(true)
                } finally {
                    file.close()
                }
            }
        }

        private fun externalChunkPath(localX: Int, localZ: Int): Path {
            val globalX = regionX * 32 + localX
            val globalZ = regionZ * 32 + localZ
            return externalDir.resolve("c.$globalX.$globalZ.mcc")
        }

        private fun readExternalChunk(localX: Int, localZ: Int, versionId: Byte): ByteArray? {
            val extPath = externalChunkPath(localX, localZ)
            if (!Files.isRegularFile(extPath)) return null
            return decompress(versionId, Files.readAllBytes(extPath))
        }

        private fun writeExternalChunk(localX: Int, localZ: Int, compressed: ByteArray) {
            val extPath = externalChunkPath(localX, localZ)
            val tmpPath = Files.createTempFile(externalDir, "tmp", null)
            try {
                Files.write(tmpPath, compressed)
                Files.move(tmpPath, extPath, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Files.deleteIfExists(tmpPath)
                throw e
            }
        }

        private fun compress(data: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out ->
                DeflaterOutputStream(out).use { it.write(data) }
            }.toByteArray()

        private fun decompress(versionId: Byte, data: ByteArray): ByteArray? =
            when (versionId.toInt()) {
                VERSION_GZIP.toInt() -> GZIPInputStream(ByteArrayInputStream(data)).readBytes()
                VERSION_DEFLATE.toInt() -> InflaterInputStream(ByteArrayInputStream(data)).readBytes()
                VERSION_NONE.toInt() -> data
                VERSION_LZ4.toInt() -> LZ4BlockInputStream(ByteArrayInputStream(data)).readBytes()
                else -> null
            }

        private fun allocateSectors(size: Int): Int {
            var pos = 0
            while (true) {
                val start = usedSectors.nextClearBit(pos)
                val end = usedSectors.nextSetBit(start)
                if (end == -1 || end - start >= size) {
                    usedSectors.set(start, start + size)
                    return start
                }
                pos = end
            }
        }

        private fun writeHeader() {
            header.position(0)
            file.write(header, 0L)
        }

        private fun padToSectorBoundary() {
            val size = file.size().toInt()
            val padded = ((size + SECTOR_BYTES - 1) / SECTOR_BYTES) * SECTOR_BYTES
            if (size != padded) {
                file.write(ByteBuffer.allocate(1), padded.toLong() - 1)
            }
        }

        private fun sectorNumber(offset: Int) = (offset ushr 8) and 0xFFFFFF
        private fun numSectors(offset: Int) = offset and 0xFF
        private fun packOffset(sector: Int, count: Int) = (sector shl 8) or count
    }
}
