package vn.signa.recognition.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Ảnh mẫu sinh trong bộ nhớ cho test. */
public final class TestImages {

  private TestImages() {}

  public static BufferedImage solid(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, width, height);
    g.dispose();
    return image;
  }

  public static byte[] encode(BufferedImage image, String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(image, format, out)) {
      throw new IllegalStateException("Không có writer cho " + format);
    }
    return out.toByteArray();
  }

  public static byte[] png(int width, int height) throws IOException {
    return encode(solid(width, height), "png");
  }

  public static byte[] jpeg(int width, int height) throws IOException {
    return encode(solid(width, height), "jpg");
  }

  public static BufferedImage decode(byte[] data) throws IOException {
    return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(data)));
  }

  /** PNG 1×1 nhưng IHDR khai kích thước khác, kèm CRC đúng: mô phỏng bom giải nén. */
  public static byte[] pngDeclaring(int width, int height) throws IOException {
    byte[] png = png(1, 1);
    // Chữ ký 8 byte, độ dài chunk 4 byte, rồi "IHDR" ở offset 12, dữ liệu IHDR ở offset 16.
    ByteBuffer buffer = ByteBuffer.wrap(png);
    buffer.putInt(16, width);
    buffer.putInt(20, height);
    CRC32 crc = new CRC32();
    crc.update(png, 12, 4 + 13);
    buffer.putInt(29, (int) crc.getValue());
    return png;
  }
}
