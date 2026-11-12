package com.personal.circlesearch

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val s = maxSide.toFloat() / longest
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Writes the image into cache/shared (exposed through FileProvider) and prunes old files. */
    fun writeShared(context: Context, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "shared")
        dir.mkdirs()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach {
            if (it.isFile && now - it.lastModified() > 10 * 60 * 1000L) it.delete()
        }
        val file = File(dir, "lens_$now.jpg")
        file.writeBytes(bytes)
        return file
    }
}
