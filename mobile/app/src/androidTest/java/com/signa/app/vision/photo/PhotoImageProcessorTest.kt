package com.signa.app.vision.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.signa.app.data.remote.backend.dto.PhotoBox
import com.signa.app.data.repository.MAX_CROP_BYTES
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoImageProcessorTest {
    @Test
    fun chuan_hoa_huong_exif_va_cat_anh_JPEG() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("photo", ".jpg", context.cacheDir)
        try {
            val random = Random(32)
            val image = Bitmap.createBitmap(IntArray(1200 * 900) { random.nextInt() or 0xff000000.toInt() },
                1200, 900, Bitmap.Config.ARGB_8888)
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val oriented = loadPhoto(context.contentResolver, file.toUri())
            assertEquals(900, oriented.width)
            assertEquals(1200, oriented.height)
            val jpeg = cropPhoto(oriented, oriented.photoSize(), PhotoBox(20.5, 50.0, 860.0, 1100.0))
            assertTrue(jpeg.size in 1..MAX_CROP_BYTES)
            assertTrue(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) != null)
        } finally {
            file.delete()
        }
    }
}
