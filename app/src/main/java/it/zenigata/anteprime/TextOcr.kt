package it.zenigata.anteprime

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Una riga riconosciuta, con il suo rettangolo in pixel della bitmap. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Riconoscimento del testo (ML Kit, modello incluso nell'app: funziona offline).
 * PdfRenderer produce solo immagini, quindi il testo si ricava così.
 */
object TextOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    /** Come read(), ma restituisce le righe una per una con le coordinate (serve alla ricerca per keyword). */
    suspend fun readLines(bmp: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                if (cont.isActive) {
                    val lines = t.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                        val r = l.boundingBox ?: return@mapNotNull null
                        OcrLine(l.text, r.left, r.top, r.right, r.bottom)
                    }
                    cont.resume(lines)
                }
            }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
