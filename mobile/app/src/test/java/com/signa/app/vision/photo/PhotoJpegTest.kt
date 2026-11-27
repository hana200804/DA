package com.signa.app.vision.photo

import com.signa.app.data.repository.MAX_CROP_BYTES
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoJpegTest {
    @Test
    fun `giam kich thuoc anh chi tiet den khi JPEG nam trong 200 KiB`() {
        val random = Random(17)
        var image = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, random.nextInt(1 shl 24))
        }
        assertTrue(encode(image, 90).size > MAX_CROP_BYTES)
        var shrunk = false
        val jpeg = fitCropJpeg { quality, shrink ->
            if (shrink) {
                shrunk = true
                val scaled = BufferedImage(image.width * 3 / 4, image.height * 3 / 4, BufferedImage.TYPE_INT_RGB)
                scaled.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(image, 0, 0, scaled.width, scaled.height, null)
                    dispose()
                }
                image = scaled
            }
            encode(image, quality)
        }
        assertTrue(shrunk)
        assertTrue(jpeg.size in 1..MAX_CROP_BYTES)
        assertNotNull(ImageIO.read(jpeg.inputStream()))
    }

    private fun encode(image: BufferedImage, quality: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(bytes).use { output ->
                writer.output = output
                val options = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(image, null, null), options)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }
}
