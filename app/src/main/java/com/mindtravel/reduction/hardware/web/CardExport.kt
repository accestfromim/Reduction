package com.mindtravel.reduction.hardware.web

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mindtravel.reduction.feature.article.ArticleBuilder
import com.mindtravel.reduction.feature.chat.data.CardRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CardExport(private val context: Context, private val cards: CardRepository, private val articles: ArticleBuilder) {
    fun save(name: String, format: String): String {
        val mime = mapOf("zip" to "application/zip", "png" to "image/png", "pdf" to "application/pdf").getValue(format)
        cards.requireShareable(name)
        val source = requireNotNull(cards.document(name))
        val public = articles.publicSource(source)
        val temp = File.createTempFile("card-", ".$format", context.cacheDir)
        try {
            temp.outputStream().use { output ->
                if (format == "zip") bundle(name, public, public != source, output)
                else pages(name, public, format, output)
            }
            val filename = name.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") + "-" + System.currentTimeMillis() + "." + format
            if (Build.VERSION.SDK_INT < 29) {
                val dest = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),filename)
                temp.copyTo(dest)
                return dest.path
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,filename)
                put(MediaStore.Downloads.MIME_TYPE,mime)
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            try {
                requireNotNull(resolver.openOutputStream(uri)).use { out -> temp.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING,0)
                check(resolver.update(uri,values,null,null) == 1)
            } catch (error: Throwable) {
                resolver.delete(uri,null,null)
                throw error
            }
            return "Download/$filename"
        } finally { temp.delete() }
    }

    private fun bundle(name: String, body: String, redacted: Boolean, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun text(path: String, data: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(data.toByteArray())
                zip.closeEntry()
            }
            text("card.html", body)
            val manifest = JSONArray()
            fun add(id: String, file: File, label: String) {
                val path = "attachments/$id/" + File(label).name
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                manifest.put(JSONObject().put("id",id).put("name",label).put("path",path))
            }
            for (id in cards.mediaIn(body)) add(id,requireNotNull(cards.mediaFile(id)),id)
            for (dir in cards.attachmentDirectory(name).listFiles().orEmpty()) {
                if (!dir.isDirectory || (redacted && !body.contains("media:"+dir.name))) continue
                val meta = JSONObject(File(dir,"meta.json").readText())
                add(dir.name,File(dir,"original"),meta.getString("name"))
            }
            text("manifest.json",manifest.toString())
        }
    }

    private fun pages(name: String, source: String, format: String, out: OutputStream) {
        val renderer = CardPageRenderer(context,cards)
        val pdf = if (format == "pdf") PdfDocument() else null
        val slices = mutableListOf<Bitmap>()
        try {
            var index = 1
            do {
                val (image,count) = renderer.render(name,source,index)
                if (pdf == null) require(count <= 8) { "长图超出手机位图内存上限，请导出 PDF" }
                val bytes = Base64.getDecoder().decode(image.base64)
                val part = requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                if (pdf == null) slices.add(part) else {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(part.width,part.height,index).create())
                    page.canvas.drawBitmap(part,0f,0f,null)
                    pdf.finishPage(page)
                    part.recycle()
                }
                index++
            } while (index <= count)
            if (pdf != null) pdf.writeTo(out) else {
                val image = Bitmap.createBitmap(slices.first().width,slices.sumOf { it.height },Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(image)
                    var y = 0
                    for (part in slices) { canvas.drawBitmap(part,0f,y.toFloat(),null); y += part.height }
                    check(image.compress(Bitmap.CompressFormat.PNG,100,out))
                } finally { image.recycle() }
            }
        } finally { pdf?.close(); slices.forEach { it.recycle() } }
    }
}
