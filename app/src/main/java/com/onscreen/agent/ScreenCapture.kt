package com.onscreen.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.ImageReader
import android.media.projection.MediaProjectionManager

/** 720p-capped MediaProjection hook. Screenshots are downscaled + recycled; VLM gets JPEG ref only. */
object ScreenCapture {
    const val REQ = 4401
    fun requestPermission(a: Activity) {
        val mpm = a.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        a.startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }
    // Full ImageReader wiring intentionally left to the integrator: keep cloud drop small.
    // Budget: max 960px long edge, JPEG q60, 1 frame per agent step, close() every frame.
}
