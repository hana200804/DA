package com.signa.app.vision.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.signa.app.data.remote.backend.dto.PhotoBox
import com.signa.app.data.remote.backend.dto.PhotoSize
import com.signa.app.data.repository.MAX_CROP_BYTES
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val MAX_PHOTO_BYTES = 10_000_000
private const val MAX_PHOTO_EDGE = 2048

internal class PhotoTooLarge : Exception()

internal fun loadPhoto(resolver: ContentResolver, uri: Uri): Bitmap {
    val bytes = requireNotNull(resolver.openInputStream(uri)).use(::readPhoto)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0)
    options.inSampleSize = 1
    val longEdge = maxOf(options.outWidth, options.outHeight)
    while (longEdge / options.inSampleSize > MAX_PHOTO_EDGE) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull()
    val rotation = exif?.rotationDegrees ?: 0
    val flipped = exif?.isFlipped == true
    if (!flipped && rotation == 0) return bitmap

    val transform = Matrix().apply {
        if (flipped) postScale(-1f, 1f)
        postRotate(rotation.toFloat())
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
}

private fun readPhoto(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) return output.toByteArray()
        if (output.size() + count > MAX_PHOTO_BYTES) throw PhotoTooLarge()
        output.write(buffer, 0, count)
    }
}

internal fun Bitmap.photoSize() = PhotoSize(width, height)

internal fun Bitmap.encodePhoto(): ByteArray = encodeJpeg(90).also {
    if (it.size > MAX_PHOTO_BYTES) throw PhotoTooLarge()
}

internal fun cropPhoto(bitmap: Bitmap, image: PhotoSize, box: PhotoBox): ByteArray {
    require(image.width > 0 && image.height > 0)
    val scaleX = bitmap.width.toDouble() / image.width
    val scaleY = bitmap.height.toDouble() / image.height
    val left = floor(box.x * scaleX).toInt().coerceIn(0, bitmap.width)
    val top = floor(box.y * scaleY).toInt().coerceIn(0, bitmap.height)
    val right = ceil((box.x + box.width) * scaleX).toInt().coerceIn(0, bitmap.width)
    val bottom = ceil((box.y + box.height) * scaleY).toInt().coerceIn(0, bitmap.height)
    require(right > left && bottom > top)
    var crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    if (crop.width.toLong() * crop.height > 4_000_000) crop = crop.scaledDown()
    return fitCropJpeg { quality, shrink ->
        if (shrink) crop = crop.scaledDown()
        crop.encodeJpeg(quality)
    }
}

internal fun fitCropJpeg(encode: (quality: Int, shrink: Boolean) -> ByteArray): ByteArray {
    for (pass in 0..8) {
        for (quality in 90 downTo 30 step 15) {
            val jpeg = encode(quality, pass > 0 && quality == 90)
            if (jpeg.isNotEmpty() && jpeg.size <= MAX_CROP_BYTES) return jpeg
        }
    }
    error("Không thể nén ảnh cắt")
}

private fun Bitmap.scaledDown(): Bitmap = Bitmap.createScaledBitmap(
    this, (width * .75).roundToInt().coerceAtLeast(1), (height * .75).roundToInt().coerceAtLeast(1), true
)

private fun Bitmap.encodeJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
    check(compress(Bitmap.CompressFormat.JPEG, quality, output))
    output.toByteArray()
}
