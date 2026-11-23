package org.openvfr.efb.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

data class CalibratedPdfLayer(
    val id: String,
    val name: String,
    val bitmap: Bitmap,
    val quad: LatLngQuad,
    val opacity: Float = 0.85f,
    val rmseMeters: Double = 0.0,
    val modelName: String = ""
)

/**
 * Gère le rendu haute résolution des pages PDF (VAC / OACI), le détourage des marges
 * et la superposition multi-calques dans le pipeline OpenGL de MapLibre.
 */
object PdfOverlayManager {

    fun getPdfPageCount(context: Context, uri: Uri): Int {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }
    }

    fun renderPdfPageToBitmap(
        context: Context,
        uri: Uri,
        pageIndex: Int = 0,
        maxDimensionPx: Int = 2400,
        cropMarginsPercent: Float = 0f
    ): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(
                        maxDimensionPx.toFloat() / page.width,
                        maxDimensionPx.toFloat() / page.height
                    ).coerceAtLeast(1f)

                    val width = (page.width * scale).toInt()
                    val height = (page.height * scale).toInt()

                    val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(rawBitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    if (cropMarginsPercent > 0.01f) {
                        applyMarginMask(rawBitmap, cropMarginsPercent)
                    } else {
                        rawBitmap
                    }
                }
            }
        }
    }

    /**
     * Masque les cartouches et marges blanches périphériques d'une carte VAC.
     */
    fun applyMarginMask(source: Bitmap, marginFraction: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val mx = source.width * marginFraction.coerceIn(0f, 0.35f)
        val my = source.height * marginFraction.coerceIn(0f, 0.35f)
        val rect = RectF(mx, my, source.width - mx, source.height - my)
        canvas.drawRoundRect(rect, 24f, 24f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    fun syncLayersToMap(style: Style, layers: List<CalibratedPdfLayer>) {
        // Retire les anciens calques absents de la liste
        val activeIds = layers.map { it.id }.toSet()
        style.layers.filter { it.id.startsWith("pdf-layer-") }.forEach { layer ->
            val id = layer.id.removePrefix("pdf-layer-")
            if (id !in activeIds) {
                style.removeLayer(layer)
                style.removeSource("pdf-source-$id")
            }
        }

        for (layerData in layers) {
            val srcId = "pdf-source-${layerData.id}"
            val lyrId = "pdf-layer-${layerData.id}"
            val existingSource = style.getSourceAs<ImageSource>(srcId)
            if (existingSource != null) {
                existingSource.setImage(layerData.bitmap)
                existingSource.setCoordinates(layerData.quad)
                style.getLayerAs<RasterLayer>(lyrId)?.setProperties(rasterOpacity(layerData.opacity))
            } else {
                val source = ImageSource(srcId, layerData.quad, layerData.bitmap)
                style.addSource(source)
                val layer = RasterLayer(lyrId, srcId).withProperties(rasterOpacity(layerData.opacity))
                style.addLayer(layer)
            }
        }
    }
}
