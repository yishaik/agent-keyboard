package helium314.keyboard.agent.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A full-resolution PNG screenshot is often 1–4 MB, more than the broker accepts
 * (MAX_BODY_BYTES, 512 KB by default). For upload it is downscaled to [MAX_EDGE] px on the long
 * edge and JPEG-encoded, stepping quality and size down until it fits [MAX_BYTES]. A phone screen
 * at 1280 px stays readable for the vision model and typically lands at 80–250 KB.
 */
object ScreenshotCodec {
    const val MAX_EDGE = 1280
    /** Base64 grows this by 4/3 (~453 KB), leaving room for the JSON around it under 512 KB. */
    const val MAX_BYTES = 340 * 1024

    fun forUpload(png: ByteArray): ByteArray {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return png
        try {
            for (edge in intArrayOf(MAX_EDGE, 1024, 800)) {
                val scaled = scale(src, edge)
                try {
                    for (q in intArrayOf(80, 65, 50)) {
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                        if (out.size() <= MAX_BYTES) return out.toByteArray()
                    }
                } finally {
                    if (scaled !== src) scaled.recycle()
                }
            }
            val last = scale(src, 640)
            val out = ByteArrayOutputStream()
            last.compress(Bitmap.CompressFormat.JPEG, 40, out)
            if (last !== src) last.recycle()
            return out.toByteArray()
        } finally {
            src.recycle()
        }
    }

    private fun scale(b: Bitmap, edge: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= edge) return b
        val f = edge.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
    }
}
