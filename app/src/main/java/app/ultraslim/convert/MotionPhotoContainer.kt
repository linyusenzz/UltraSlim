package app.ultraslim.convert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Container operations shared by production and JVM regression tests. No Android dependencies. */
data class MotionPhotoInfo(val presentationTimestampUs: Long, val videoLength: Long, val videoOffset: Long)

internal object MotionPhotoContainer {
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()

    private fun xml(text: String): Document {
        require(text.length <= 1024 * 1024 && !text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray()))
    }

    private fun elements(doc: Document, namespace: String, name: String): List<Element> {
        val nodes = doc.getElementsByTagNameNS(namespace, name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun property(doc: Document, name: String): String? {
        elements(doc, RDF, "Description").forEach {
            if (it.hasAttributeNS(CAMERA, name)) return it.getAttributeNS(CAMERA, name)
        }
        return elements(doc, CAMERA, name).firstOrNull()?.textContent
    }

    fun read(channel: FileChannel, offset: Long, size: Int): ByteArray {
        require(offset >= 0 && size >= 0)
        val b = ByteBuffer.allocate(size)
        while (b.hasRemaining()) {
            if (channel.read(b, offset + b.position()) <= 0) throw EOFException("文件读取不完整")
        }
        return b.array()
    }

    private data class Box(val start: Long, val size: Long, val header: Int, val type: String)
    private fun box(channel: FileChannel, offset: Long, end: Long): Box? {
        if (offset < 0 || end - offset < 8) return null
        val bytes = read(channel, offset, 8)
        var size = ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
        var header = 8
        if (size == 1L) {
            if (end - offset < 16) return null
            size = ByteBuffer.wrap(read(channel, offset + 8, 8)).long
            header = 16
        } else if (size == 0L) size = end - offset
        if (size < header || size > end - offset) return null
        return Box(offset, size, header, String(bytes, 4, 4, Charsets.US_ASCII))
    }

    fun validVideo(channel: FileChannel, offset: Long, length: Long): Boolean {
        if (offset < 0 || length < 24 || offset > channel.size() - length) return false
        val end = offset + length
        var pos = offset
        var moov = false
        var mdat = false
        while (pos < end) {
            val b = box(channel, pos, end) ?: return false
            if (pos == offset && (b.type != "ftyp" || b.size < b.header + 8)) return false
            moov = moov || b.type == "moov"
            mdat = mdat || b.type == "mdat"
            pos += b.size
        }
        return moov && mdat
    }

    fun detect(channel: FileChannel, containerXmp: String? = null): MotionPhotoInfo? {
        val size = channel.size()
        if (size < 8) return null
        val jpeg = read(channel, 0, 2).contentEquals(byteArrayOf(-1, -40))
        val packet = if (jpeg) jpegXmp(channel) else containerXmp
        val doc = packet?.let { runCatching { xml(it) }.getOrNull() }
        val motion = doc?.let { property(it, "MotionPhoto") ?: property(it, "MicroVideo") }
        if (motion != null && motion != "1") return null
        val timestamp = doc?.let { property(it, "MotionPhotoPresentationTimestampUs")
            ?: property(it, "MicroVideoPresentationTimestampUs") }?.toLongOrNull()?.coerceAtLeast(-1) ?: -1L
        val length = doc?.let { d ->
            elements(d, CONTAINER, "Item").firstOrNull {
                it.getAttributeNS(ITEM, "Semantic").equals("MotionPhoto", true)
            }?.getAttributeNS(ITEM, "Length")?.toLongOrNull()
                ?: property(d, "MicroVideoOffset")?.toLongOrNull()
        }
        if (length != null && length > 0 && length < size && validVideo(channel, size - length, length)) {
            return MotionPhotoInfo(timestamp, length, size - length)
        }
        if (!jpeg) {
            var pos = 0L
            while (pos < size) {
                val b = box(channel, pos, size) ?: break
                if (b.type == "mpvd" && pos + b.size == size) {
                    val offset = pos + b.header
                    if (validVideo(channel, offset, b.size - b.header))
                        return MotionPhotoInfo(timestamp, b.size - b.header, offset)
                }
                pos += b.size
            }
        }
        if (motion == "1") {
            // Overlap seven bytes so a split size+ftyp header cannot be missed.
            val start = maxOf(0, size - 30L * 1024 * 1024)
            var end = size
            while (end > start) {
                val begin = maxOf(start, end - 65536)
                val bytes = read(channel, begin, (end - begin).toInt())
                for (i in bytes.size - 8 downTo 0) {
                    if (bytes[i + 4] == 102.toByte() && bytes[i + 5] == 116.toByte() &&
                        bytes[i + 6] == 121.toByte() && bytes[i + 7] == 112.toByte()) {
                        val offset = begin + i
                        if (validVideo(channel, offset, size - offset)) return MotionPhotoInfo(timestamp, size - offset, offset)
                    }
                }
                if (begin == start) break
                end = begin + 7
            }
        }
        return null
    }

    private data class Segment(val start: Int, val end: Int, val marker: Int)
    private fun jpegSegments(bytes: ByteArray): List<Segment> {
        require(bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte())
        val result = mutableListOf<Segment>()
        var pos = 2
        while (pos + 4 <= bytes.size) {
            require(bytes[pos] == (-1).toByte())
            val marker = bytes[pos + 1].toInt() and 255
            if (marker == 0xda || marker == 0xd9) break
            val length = ((bytes[pos + 2].toInt() and 255) shl 8) or (bytes[pos + 3].toInt() and 255)
            require(length >= 2 && length <= bytes.size - pos - 2)
            result += Segment(pos, pos + length + 2, marker)
            pos += length + 2
        }
        return result
    }
    private fun isXmp(bytes: ByteArray, s: Segment) = s.marker == 0xe1 &&
        s.end - s.start >= 4 + xmpHeader.size && xmpHeader.indices.all { bytes[s.start + 4 + it] == xmpHeader[it] }

    private fun jpegXmp(channel: FileChannel): String? {
        var pos = 2L
        while (pos + 4 <= channel.size()) {
            val h = read(channel, pos, 4)
            if (h[0] != (-1).toByte()) return null
            val marker = h[1].toInt() and 255
            if (marker == 0xda || marker == 0xd9) return null
            val length = ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
            if (length < 2 || pos + 2 + length > channel.size()) return null
            if (marker == 0xe1) {
                val data = read(channel, pos + 4, length - 2)
                if (data.size >= xmpHeader.size && xmpHeader.indices.all { data[it] == xmpHeader[it] })
                    return String(data, xmpHeader.size, data.size - xmpHeader.size, Charsets.UTF_8)
            }
            pos += length + 2
        }
        return null
    }

    fun xmp(info: MotionPhotoInfo, mime: String, gainmapLength: Long = 0, existing: String? = null): ByteArray {
        val doc = xml(existing ?: """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF"><rdf:Description rdf:about=""/></rdf:RDF></x:xmpmeta>""")
        elements(doc, CONTAINER, "Directory").forEach { it.parentNode.removeChild(it) }
        elements(doc, RDF, "Description").forEach { e ->
            for (i in e.attributes.length - 1 downTo 0) {
                val a = e.attributes.item(i)
                if (a.namespaceURI == CAMERA && (a.localName.startsWith("MotionPhoto") || a.localName.startsWith("MicroVideo"))) e.removeAttributeNode(a as org.w3c.dom.Attr)
            }
        }
        listOf("MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs", "MicroVideo", "MicroVideoOffset", "MicroVideoVersion", "MicroVideoPresentationTimestampUs").forEach { name ->
            elements(doc, CAMERA, name).forEach { it.parentNode.removeChild(it) }
        }
        val rdf = elements(doc, RDF, "RDF").first()
        val desc = doc.createElementNS(RDF, "rdf:Description").also { rdf.appendChild(it) }
        desc.setAttributeNS(CAMERA, "Camera:MotionPhoto", "1")
        desc.setAttributeNS(CAMERA, "Camera:MotionPhotoVersion", "1")
        desc.setAttributeNS(CAMERA, "Camera:MotionPhotoPresentationTimestampUs", info.presentationTimestampUs.toString())
        val directory = doc.createElementNS(CONTAINER, "Container:Directory").also { desc.appendChild(it) }
        val seq = doc.createElementNS(RDF, "rdf:Seq").also { directory.appendChild(it) }
        fun item(type: String, semantic: String, length: Long?) {
            val li = doc.createElementNS(RDF, "rdf:li").also { seq.appendChild(it); it.setAttributeNS(RDF, "rdf:parseType", "Resource") }
            val item = doc.createElementNS(CONTAINER, "Container:Item").also { li.appendChild(it) }
            item.setAttributeNS(ITEM, "Item:Mime", type)
            item.setAttributeNS(ITEM, "Item:Semantic", semantic)
            length?.let { item.setAttributeNS(ITEM, "Item:Length", it.toString()) }
            if (semantic == "Primary" && mime != "image/jpeg") item.setAttributeNS(ITEM, "Item:Padding", "8")
        }
        item(mime, "Primary", null)
        if (gainmapLength > 0) item("image/jpeg", "GainMap", gainmapLength)
        item("video/mp4", "MotionPhoto", info.videoLength)
        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }
            .transform(DOMSource(doc), StreamResult(output))
        return output.toByteArray()
    }

    /** Replace the existing packet and fix MPF sizes/offsets, keeping encoded JPEG scans intact. */
    fun jpegWithXmp(bytes: ByteArray, info: MotionPhotoInfo): ByteArray {
        val segments = jpegSegments(bytes)
        val old = segments.singleOrNull { isXmp(bytes, it) }
        require(segments.count { isXmp(bytes, it) } <= 1) { "JPEG 存在多个 XMP 数据包" }
        val mpf = segments.firstOrNull { it.marker == 0xe2 && it.end - it.start >= 8 && String(bytes, it.start + 4, 4, Charsets.US_ASCII) == "MPF\u0000" }
        val entries = mutableListOf<Int>()
        var tiff = 0
        var order = ByteOrder.BIG_ENDIAN
        if (mpf != null) {
            tiff = mpf.start + 8
            require(tiff + 8 <= mpf.end)
            order = if (bytes[tiff] == 73.toByte() && bytes[tiff + 1] == 73.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val b = ByteBuffer.wrap(bytes).order(order)
            val ifd = tiff + b.getInt(tiff + 4)
            require(ifd >= tiff && ifd + 2 <= mpf.end)
            val count = b.getShort(ifd).toInt() and 65535
            require(ifd.toLong() + 2 + count * 12L <= mpf.end)
            for (i in 0 until count) {
                val entry = ifd + 2 + i * 12
                if ((b.getShort(entry).toInt() and 65535) == 0xb002) {
                    val n = b.getInt(entry + 4)
                    val start = tiff.toLong() + b.getInt(entry + 8)
                    require(n >= 16 && n % 16 == 0 && start >= tiff && start + n <= mpf.end)
                    repeat(n / 16) { entries += start.toInt() + it * 16 }
                }
            }
        }
        val gmLength = entries.drop(1).firstOrNull()?.let { ByteBuffer.wrap(bytes).order(order).getInt(it + 4).toLong() and 0xffffffffL } ?: 0L
        val existing = old?.let { String(bytes, it.start + 4 + xmpHeader.size, it.end - it.start - 4 - xmpHeader.size, Charsets.UTF_8) }
        val packet = xmpHeader + xmp(info, "image/jpeg", gmLength, existing)
        require(packet.size + 2 <= 65535) { "XMP 超过 JPEG APP1 容量" }
        val marker = ByteBuffer.allocate(packet.size + 4).put(0xff.toByte()).put(0xe1.toByte()).putShort((packet.size + 2).toShort()).put(packet).array()
        val start = old?.start ?: 2
        val end = old?.end ?: start
        val markerEnd = Math.addExact(start, marker.size)
        val result = ByteArray(Math.addExact(markerEnd, bytes.size - end))
        bytes.copyInto(result, endIndex = start)
        marker.copyInto(result, destinationOffset = start)
        bytes.copyInto(result, destinationOffset = markerEnd, startIndex = end)
        val delta = marker.size - (end - start)
        fun shifted(pos: Int) = if (pos >= end) pos + delta else pos
        val original = ByteBuffer.wrap(bytes).order(order)
        val updated = ByteBuffer.wrap(result).order(order)
        entries.forEachIndexed { i, entry ->
            val dst = shifted(entry)
            if (i == 0) updated.putInt(dst + 4, Math.addExact(original.getInt(entry + 4), delta))
            else {
                val absolute = Math.addExact(tiff, original.getInt(entry + 8))
                require(absolute >= end && absolute < bytes.size)
                updated.putInt(dst + 8, shifted(absolute) - shifted(tiff))
            }
        }
        return result
    }

    fun copyVideo(channel: FileChannel, info: MotionPhotoInfo, output: OutputStream) {
        require(validVideo(channel, info.videoOffset, info.videoLength)) { "实况视频不完整" }
        var offset = info.videoOffset
        var remaining = info.videoLength
        while (remaining > 0) {
            val bytes = read(channel, offset, minOf(65536L, remaining).toInt())
            output.write(bytes)
            offset += bytes.size
            remaining -= bytes.size
        }
    }
}
