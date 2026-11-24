package vn.signa.recognition.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/**
 * Kiểm tra và thu nhỏ ảnh nhận diện hoàn toàn trong bộ nhớ (NFR-09). Dùng stream bộ nhớ của ImageIO
 * để không sinh file cache trên đĩa.
 */
@Component
public class ImageProcessor {

  /** Bằng giới hạn của dịch vụ nhận diện ({@code ai/service/images.py}). */
  public static final long MAX_BYTES = 10_000_000L;

  /** Chặn bom giải nén trước khi cấp bộ nhớ cho ảnh, bằng giới hạn của dịch vụ. */
  public static final long MAX_PIXELS = 25_000_000L;

  public static final int MAX_EDGE = 1280;
  static final float JPEG_QUALITY = 0.9f;

  public PreparedImage prepare(byte[] data) {
    ImageFormat format = ImageFormat.detect(data);
    ImageReader reader = reader(format);
    try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
      reader.setInput(input, true, true);
      int width = reader.getWidth(0);
      int height = reader.getHeight(0);
      if (width <= 0 || height <= 0) {
        throw ImageFormat.unsupported();
      }
      if ((long) width * height > MAX_PIXELS) {
        throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Ảnh nhận diện tối đa 25 triệu pixel");
      }
      int longEdge = Math.max(width, height);
      if (longEdge <= MAX_EDGE) {
        return new PreparedImage(
            data, format.mediaType(), "image." + format.extension(), width, height, width, height);
      }
      double ratio = (double) MAX_EDGE / longEdge;
      int sentWidth = Math.max(1, (int) Math.round(width * ratio));
      int sentHeight = Math.max(1, (int) Math.round(height * ratio));
      BufferedImage resized = resize(reader.read(0), sentWidth, sentHeight);
      return new PreparedImage(
          encodeJpeg(resized),
          MediaType.IMAGE_JPEG,
          "image.jpg",
          width,
          height,
          sentWidth,
          sentHeight);
    } catch (IOException | RuntimeException e) {
      if (e instanceof ApiException api) {
        throw api;
      }
      throw ImageFormat.unsupported();
    } finally {
      reader.dispose();
    }
  }

  private static ImageReader reader(ImageFormat format) {
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.extension());
    if (!readers.hasNext()) {
      throw new IllegalStateException("Thiếu ImageReader cho " + format);
    }
    return readers.next();
  }

  /** Thu nhỏ từng nửa bằng nội suy song tuyến để tránh răng cưa, nền trắng cho ảnh có alpha. */
  static BufferedImage resize(BufferedImage source, int targetWidth, int targetHeight) {
    BufferedImage current = source;
    int w = source.getWidth();
    int h = source.getHeight();
    do {
      w = Math.max(targetWidth, w / 2);
      h = Math.max(targetHeight, h / 2);
      BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = next.createGraphics();
      try {
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(current, 0, 0, w, h, Color.WHITE, null);
      } finally {
        g.dispose();
      }
      current = next;
    } while (w != targetWidth || h != targetHeight);
    return current;
  }

  static byte[] encodeJpeg(BufferedImage image) throws IOException {
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
      writer.setOutput(output);
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      writer.write(null, new IIOImage(image, null, null), param);
    } finally {
      writer.dispose();
    }
    return bytes.toByteArray();
  }
}
