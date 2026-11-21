package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.model.chat.ChatImage
import java.awt.Rectangle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/** Return one legible slice of the rendered artifact through the normal tool-image channel. */
internal fun cardPngPage(file: File, page: Int): Pair<ChatImage,Int> {
    val height = 1600
    var count = 0
    var image: ChatImage? = null
    fun inspect(input: InputStream) {
        ImageIO.createImageInputStream(input).use { stream ->
            val reader = ImageIO.getImageReadersByFormatName("png").next()
            try {
                reader.input = stream
                val h = reader.getHeight(0)
                val pages = (h + height - 1) / height
                if (page in (count + 1)..(count + pages)) {
                    val y = (page - count - 1) * height
                    val param = reader.defaultReadParam
                    param.sourceRegion = Rectangle(0,y,reader.getWidth(0),minOf(height,h-y))
                    val bytes = ByteArrayOutputStream()
                    ImageIO.write(reader.read(0,param),"png",bytes)
                    image = ChatImage("卡片渲染 · 第 ${page} 段","image/png",Base64.getEncoder().encodeToString(bytes.toByteArray()))
                }
                count += pages
            } finally { reader.dispose() }
        }
    }
    if (file.extension == "zip") ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".png") }.sortedBy { it.name }
            .forEach { zip.getInputStream(it).use(::inspect) }
    } else file.inputStream().use(::inspect)
    return requireNotNull(image) { "渲染结果共 $count 段，请指定有效 page" } to count
}
