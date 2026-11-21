package com.mindtravel.reduction

import android.app.Instrumentation
import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.hardware.web.CardPageRenderer
import java.nio.file.Files
import java.util.Base64

class CardRenderInstrumentation : Instrumentation() {
    private var audioTest = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        audioTest = arguments?.getString("case") == "warm-audio"
        start()
    }
    override fun onStart() {
        val test = runCatching {
            if (audioTest) {
                verifyWarmAudio(targetContext)
                return@runCatching
            }
            val root = Files.createTempDirectory(targetContext.cacheDir.toPath(), "card-render").toFile()
            try {
                val cards = CardRepository(root)
                val renderer = CardPageRenderer(targetContext, cards)
                val source = "<html><body><p>Hello CARD</p>" +
                    "<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='200' height='200' fill='red'/></svg>" +
                    "<p>Long card</p>".repeat(160) + "</body></html>"
                val (first, pages) = renderer.render("Test", source, 1)
                check(pages > 1)
                val bytes = Base64.getDecoder().decode(first.base64)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                check((0 until bitmap.height step 4).any { y ->
                    (0 until bitmap.width step 4).any { x -> bitmap.getPixel(x,y) == android.graphics.Color.RED }
                }) { "SVG missing; sampled colors " + (0 until bitmap.height step 10).flatMap { y -> (0 until bitmap.width step 10).map { x -> bitmap.getPixel(x,y) } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8) }
                val (last, count) = renderer.render("Test", source, pages)
                check(pages == count && first.base64 != last.base64)
                val articles = com.mindtravel.reduction.feature.article.ArticleBuilder(
                    { path -> targetContext.assets.open(path).bufferedReader().use { it.readText() } },
                    { path -> targetContext.assets.open(path).use { it.readBytes() } }, cards::read)
                val exports = com.mindtravel.reduction.hardware.web.CardExport(targetContext,cards,articles)
                val media = requireNotNull(cards.saveMedia(bytes,"png"))
                val sample = source.replace("</body>", "<card-private>hidden-sample</card-private><img data-attachment='media:$media'></body>")
                check(cards.save("export-test",sample))
                for (format in listOf("zip","png","pdf")) {
                    val file = java.io.File(android.os.Environment.getExternalStorageDirectory(),exports.save("export-test",format))
                    check(file.isFile && file.length() > 0)
                    when (format) {
                        "zip" -> java.util.zip.ZipFile(file).use { archive ->
                            val body = archive.getInputStream(archive.getEntry("card.html")).bufferedReader().use { it.readText() }
                            check("hidden-sample" !in body && media in body)
                            check(archive.getInputStream(archive.getEntry("attachments/$media/$media")).use { it.readBytes() }.contentEquals(bytes))
                        }
                        "png" -> {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path,options)
                            check(options.outWidth == 720 && options.outHeight > 1200)
                        }
                        "pdf" -> android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            android.graphics.pdf.PdfRenderer(fd).use { check(it.pageCount > 1) }
                        }
                    }
                }
            } finally { root.deleteRecursively() }
        }
        finish(if (test.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("result", test.exceptionOrNull()?.stackTraceToString() ?: if (audioTest) "one recorder, wake to turn verified" else "CARD PNG first and last pages verified") })
    }
}
