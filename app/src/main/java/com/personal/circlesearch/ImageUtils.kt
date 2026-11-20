package com.personal.circlesearch

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {

    /** Saves a screenshot as PNG into Pictures/CircleSearch (no permission needed on Android 10+). */
    fun saveToGallery(context: Context, bitmap: Bitmap) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "shot_${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CircleSearch")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
        }
    }


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
