package com.signa.app.ui.sign

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.signa.app.data.repository.ReportPhoto
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SignRemoteImage(url: String, description: String, modifier: Modifier, fallback: @Composable () -> Unit) {
    val result by produceState<Result<Bitmap?>?>(null, url) {
        value = null
        value = signDataCall { withContext(Dispatchers.IO) { downloadImage(url) } }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = result?.getOrNull()
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), description, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            result == null -> CircularProgressIndicator()
            else -> fallback()
        }
    }
}

private fun downloadImage(url: String): Bitmap? {
    val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
    return try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.inputStream.use { stream ->
            val bytes = stream.readWithinLimit(ReportPhoto.MAX_BYTES) ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth < 1 || options.outHeight < 1) return null
            options.inSampleSize = Integer.highestOneBit(maxOf(options.outWidth, options.outHeight) / 1024).coerceAtLeast(1)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    } finally {
        connection.disconnect()
    }
}

internal suspend fun loadReportPhoto(resolver: ContentResolver, uri: Uri?): ReportPhoto? {
    if (uri == null) return null
    return withContext(Dispatchers.IO) {
        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readWithinLimit(ReportPhoto.MAX_BYTES) }
                ?: throw SignReportPhotoException()
            val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
            val extension = when (info.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> throw SignReportPhotoException()
            }
            if (info.outWidth !in 1..4096 || info.outHeight !in 1..4096 ||
                info.outWidth.toLong() * info.outHeight > 8_000_000) throw SignReportPhotoException()
            ReportPhoto(bytes, info.outMimeType, "sign-report.$extension")
        } catch (_: IOException) {
            throw SignReportPhotoException()
        } catch (_: SecurityException) {
            throw SignReportPhotoException()
        }
    }
}

private fun InputStream.readWithinLimit(limit: Int): ByteArray? {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) return result.toByteArray()
        if (result.size() + count > limit) return null
        result.write(buffer, 0, count)
    }
}
