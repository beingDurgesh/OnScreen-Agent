package com.onscreen.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Full MediaProjection capture. One frame per call, downscaled so the long
 * edge is <= MAX_EDGE, JPEG q60. Every surface/image/display is closed before
 * return — no bitmap or surface may escape this function (RAM rule).
 */
object ScreenCapture {
    private const val TAG = "ScreenCapture"
    const val MAX_EDGE = 960

    /** How long to wait for the compositor to deliver the first frame. */
    private const val FRAME_TIMEOUT_MS = 2000L

    @Volatile private var projection: MediaProjection? = null

    /**
     * Must be registered before createVirtualDisplay() or the projection
     * delivers no frames (Android 14+ / API 34+ requirement). Held in a field
     * so it is not garbage collected while the projection is alive.
     */
    private var callback: MediaProjection.Callback? = null

    fun intentForResult(a: Activity): Intent {
        val mpm = a.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    fun onPermissionResult(ctx: Context, resultCode: Int, data: Intent?) {
        release()
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "projection denied (resultCode=$resultCode)")
            return
        }
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.w(TAG, "getMediaProjection failed", t); null
        }
        if (mp == null) return
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "projection stopped by the system")
                projection = null
                callback = null
            }
        }
        callback = cb
        try {
            mp.registerCallback(cb, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            Log.w(TAG, "registerCallback failed", t)
        }
        projection = mp
        Log.i(TAG, "projection granted")
    }

    fun hasPermission(): Boolean = projection != null

    fun release() {
        try { projection?.stop() } catch (_: Throwable) { /* already stopped */ }
        projection = null
        callback = null
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
            // API 35+ signature: (name, w, h, dpi, flags, surface, callback, handler).
            // The old (name, w, h, dpi, surface, flags) overload was removed.
            vd = mp.createVirtualDisplay(
                "onscreen", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            // The compositor needs a moment to start pushing frames; poll until
            // one arrives instead of guessing a fixed delay.
            var img: Image? = null
            val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
            while (img == null && System.currentTimeMillis() < deadline) {
                img = reader.acquireLatestImage()
                if (img == null) Thread.sleep(60)
            }
            if (img == null) {
                Log.w(TAG, "no frame within ${FRAME_TIMEOUT_MS}ms")
                return null
            }
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
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            return null
        } finally {
            try { vd?.release() } catch (_: Throwable) { /* ignore */ }
            try { reader.close() } catch (_: Throwable) { /* ignore */ }
        }
    }
}
