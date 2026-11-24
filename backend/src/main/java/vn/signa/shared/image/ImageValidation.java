package vn.signa.shared.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** Xác thực ảnh tải lên trước khi lưu, không dùng tệp tạm. */
public final class ImageValidation {

  private ImageValidation() {}

  public static ImageFormat validate(byte[] data, int maxEdge, long maxPixels) {
    ImageFormat format = ImageFormat.detect(data);
    validate(data, format, maxEdge, maxPixels);
    return format;
  }

  public static void validate(byte[] data, ImageFormat format, int maxEdge, long maxPixels) {
    if (ImageFormat.detect(data) != format || !hasCompleteContainer(data, format)) {
      throw ImageFormat.unsupported();
    }

    var readers = ImageIO.getImageReadersByFormatName(format.extension());
    if (!readers.hasNext()) throw new IllegalStateException("Thiếu ImageReader cho " + format);
    var reader = readers.next();
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
      reader.setInput(input, true, true);
      int width = reader.getWidth(0);
      int height = reader.getHeight(0);
      if (width <= 0 || height <= 0) throw ImageFormat.unsupported();
      if (width > maxEdge || height > maxEdge || (long) width * height > maxPixels) {
        throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Kích thước ảnh vượt giới hạn");
      }

      var warning = new AtomicBoolean();
      reader.addIIOReadWarningListener((source, message) -> warning.set(true));
      BufferedImage image = reader.read(0);
      if (image == null || warning.get()) throw ImageFormat.unsupported();
    } catch (ApiException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw ImageFormat.unsupported();
    } finally {
      reader.dispose();
    }
  }

  private static boolean hasCompleteContainer(byte[] data, ImageFormat format) {
    return switch (format) {
      case JPEG -> data[data.length - 2] == (byte) 0xff && data[data.length - 1] == (byte) 0xd9;
      case PNG -> hasCompletePngChunks(data);
      case WEBP ->
          Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(4)) + 8
              == data.length;
    };
  }

  private static boolean hasCompletePngChunks(byte[] data) {
    ByteBuffer buffer = ByteBuffer.wrap(data);
    CRC32 crc = new CRC32();
    int offset = 8; // Bỏ chữ ký PNG.
    while (data.length - offset >= 12) {
      int length = buffer.getInt(offset);
      if (length < 0 || length > data.length - offset - 12) return false;
      int type = buffer.getInt(offset + 4);
      int end = offset + 8 + length;
      crc.reset();
      crc.update(data, offset + 4, 4 + length);
      if ((int) crc.getValue() != buffer.getInt(end)) return false;
      offset = end + 4;
      if (type == 0x49454e44) return length == 0 && offset == data.length; // IEND
    }
    return false;
  }
}
