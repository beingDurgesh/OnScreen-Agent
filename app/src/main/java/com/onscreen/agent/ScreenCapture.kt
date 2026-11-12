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
 *
 * Android allows **one** VirtualDisplay per MediaProjection instance: calling
 * `createVirtualDisplay` twice on the same projection throws
 * `SecurityException("Don't re-use the resultData ...")`. The display and its
 * ImageReader are therefore created once and reused for every step.
 *
 * The display is created lazily on the first capture rather than when the
 * permission is granted: creating it while the system app-picker is still on
 * screen leaves it attached to a display that never receives frames.
 */
object ScreenCapture {
    private const val TAG = "ScreenCapture"
    const val MAX_EDGE = 960

    /** How long to wait for the compositor to deliver a frame. */
    private const val FRAME_TIMEOUT_MS = 2000L

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null

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
                release()
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
        try { virtualDisplay?.release() } catch (_: Throwable) { /* ignore */ }
        virtualDisplay = null
        try { reader?.close() } catch (_: Throwable) { /* ignore */ }
        reader = null
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

        // Create the single VirtualDisplay for this projection on first use.
        var r = reader
        if (r == null) {
            try {
                val created = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                // API 35+ signature: (name, w, h, dpi, flags, surface, callback, handler).
                // The old (name, w, h, dpi, surface, flags) overload was removed.
                virtualDisplay = mp.createVirtualDisplay(
                    "onscreen", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    created.surface, null, null
                )
                reader = created
                r = created
                Log.i(TAG, "virtual display created ${w}x$h")
            } catch (t: Throwable) {
                Log.w(TAG, "createVirtualDisplay failed", t)
                return null
            }
        }

        try {
            // The compositor needs a moment to push frames; poll until one
            // arrives instead of guessing a fixed delay.
            var img: Image? = null
            val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
            while (img == null && System.currentTimeMillis() < deadline) {
                img = r.acquireLatestImage()
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
        }
    }
}
