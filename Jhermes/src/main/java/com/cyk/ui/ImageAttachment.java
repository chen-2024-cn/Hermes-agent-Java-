package com.cyk.ui;

import com.cyk.util.SensitivePathGuard;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;

/** Validated image; the payload is never printed by the input UI. */
public record ImageAttachment(String name, String dataUrl, int width, int height, int bytes) {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final long MAX_PIXELS = 25_000_000L;

    public static ImageAttachment fromFile(Path path) throws IOException {
        Path real = path.toAbsolutePath().normalize().toRealPath();
        if (SensitivePathGuard.isSensitivePath(path.toAbsolutePath().normalize())
                || SensitivePathGuard.isSensitivePath(real)) {
            throw new IOException("不能附加敏感凭据路径中的文件");
        }
        if (!Files.isRegularFile(real) || Files.size(real) > MAX_BYTES) {
            throw new IOException("图片必须是普通文件且不超过 10MB");
        }
        try (var stream = Files.newInputStream(real)) {
            return fromBytes(real.getFileName().toString(), stream.readNBytes(MAX_BYTES + 1));
        }
    }

    public static ImageAttachment fromBytes(String name, byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) {
            throw new IOException("图片为空或超过 10MB");
        }
        // Query metadata before decoding pixels, so oversized images cannot allocate a huge raster.
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("不是可识别的图片(支持 PNG/JPEG/GIF)");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mime = switch (format) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "gif" -> "image/gif";
                    default -> throw new IOException("不支持的图片格式: " + format);
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IOException("图片像素超过 2500 万限制");
                }
                if (reader.read(0) == null) throw new IOException("图片无法解码");
                return new ImageAttachment(name, "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(data), width, height, data.length);
            } finally {
                reader.dispose();
            }
        }
    }

    public String summary() {
        return name + " (" + width + "x" + height + ", " + (bytes + 1023) / 1024 + "KB)";
    }

    @Override public String toString() { return summary(); }
}
