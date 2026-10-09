package com.onscreen.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/**
 * Full MediaProjection capture. One frame per call, downscaled so the long
 * edge is <= MAX_EDGE, JPEG q60. Every surface/image/display is closed before
 * return — no bitmap or surface may escape this function (RAM rule).
 */
object ScreenCapture {
    const val MAX_EDGE = 960

    @Volatile private var projection: MediaProjection? = null

    fun intentForResult(a: Activity): Intent {
        val mpm = a.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    fun onPermissionResult(ctx: Context, resultCode: Int, data: Intent?) {
        release()
        if (resultCode != Activity.RESULT_OK || data == null) return
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try { mpm.getMediaProjection(resultCode, data) } catch (_: Throwable) { null }
    }

    fun hasPermission(): Boolean = projection != null

    fun release() {
        try { projection?.stop() } catch (_: Throwable) { /* already stopped */ }
        projection = null
    }

    /** Returns JPEG bytes, or null when permission is missing / no frame ready. */
    fun captureJpeg(ctx: Context): ByteArray? {
        val mp = projection ?: return null
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (w <= 0 || h <= 0) return null
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: android.hardware.display.VirtualDisplay? = null
        try {
            vd = mp.createVirtualDisplay(
                "onscreen", w, h, dm.densityDpi, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
            Thread.sleep(350) // let the compositor deliver a frame
            val img = reader.acquireLatestImage() ?: return null
            try {
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixStride = plane.pixelStride
                val paddedW = if (pixStride > 0) rowStride / pixStride else w
                var bmp = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                if (paddedW != w) {
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    bmp.recycle()
                    bmp = cropped
                }
                val scale = MAX_EDGE.toFloat() / maxOf(w, h).toFloat()
                val sw = (w * scale).toInt().coerceAtLeast(1)
                val sh = (h * scale).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                if (small !== bmp) bmp.recycle()
                try {
                    val out = java.io.ByteArrayOutputStream(sw * sh)
                    small.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    return out.toByteArray()
                } finally {
                    small.recycle()
                }
            } finally {
                img.close()
            }
        } catch (_: Throwable) {
            return null
        } finally {
            try { vd?.release() } catch (_: Throwable) { /* ignore */ }
            try { reader.close() } catch (_: Throwable) { /* ignore */ }
        }
    }
}
