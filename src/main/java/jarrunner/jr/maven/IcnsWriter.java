package jarrunner.jr.maven;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Makes a macOS .icns from the icon the pom names (PRP-36), so one icon serves the Windows exe and the Mac app.
 *  Accepts a .icns (used as it is), a .png, or a .ico whose entries are PNG (the form of jr's own icon/jr-icon.ico; a
 *  BMP-only .ico is refused with a message). The largest image is scaled down to each size macOS uses, never up,
 *  and stored as PNG entries: icp4 16, icp5 32, icp6 64, ic07 128, ic08 256, ic09 512, ic10 1024. */
final class IcnsWriter {
    private static final int[] SIZES = {16, 32, 64, 128, 256, 512, 1024};
    private static final String[] TYPES = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};

    private IcnsWriter() {}

    static byte[] from(File icon) throws IOException {
        if (icon.getName().toLowerCase().endsWith(".icns")) return Files.readAllBytes(icon.toPath());
        var image = image(icon);
        var max = Math.min(image.getWidth(), image.getHeight());
        var out = new ByteArrayOutputStream();
        out.write(new byte[8]); // 'icns' + total length, filled in below
        for (var i = 0; i < SIZES.length && SIZES[i] <= max; i++) entry(out, TYPES[i], png(scale(image, SIZES[i])));
        return finish(out);
    }

    /** A small .icns with ONE PNG entry of the given size (512 or 256), for the binary's own section (PRP-42): macOS
     *  scales that one image for every view, as Windows does with a single 256 .ico entry. A .png that already has
     *  exactly this size is used byte for byte, so an icon optimized by hand (a palette PNG, say) stays that small. */
    static byte[] single(File icon, int size) throws IOException {
        var bytes = Files.readAllBytes(icon.toPath());
        byte[] png;
        if (icon.getName().toLowerCase().endsWith(".png") && pngSize(bytes) == size) {
            png = bytes;
        } else {
            var image = image(icon);
            png = png(scale(image, Math.min(size, Math.min(image.getWidth(), image.getHeight()))));
        }
        var out = new ByteArrayOutputStream();
        out.write(new byte[8]);
        var actual = pngSize(png); // a smaller source is never scaled up, so the entry is typed by what it holds
        var type = 0;
        while (type + 1 < SIZES.length && SIZES[type + 1] <= actual) type++;
        entry(out, TYPES[type], png);
        return finish(out);
    }

    /** The icon as an image: a .png, or the largest PNG entry of a .ico or .icns. */
    private static BufferedImage image(File icon) throws IOException {
        var bytes = Files.readAllBytes(icon.toPath());
        var name = icon.getName().toLowerCase();
        var source = name.endsWith(".ico") ? largestIcoPng(bytes, icon) : name.endsWith(".icns") ? largestIcnsPng(bytes, icon) : bytes;
        var image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException(icon + " is not an image Java can read (use a .png, .ico or .icns)");
        if (Math.min(image.getWidth(), image.getHeight()) < 16) throw new IOException(icon + " is smaller than 16x16");
        return image;
    }

    private static void entry(ByteArrayOutputStream out, String type, byte[] png) throws IOException {
        out.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(8 + png.length).array());
        out.write(png);
    }

    private static byte[] finish(ByteArrayOutputStream out) {
        var icns = out.toByteArray();
        ByteBuffer.wrap(icns).order(ByteOrder.BIG_ENDIAN).put("icns".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(icns.length);
        return icns;
    }

    /** A PNG's width from its IHDR, or -1. */
    static int pngSize(byte[] png) {
        return png.length > 24 && (png[0] & 0xFF) == 0x89 && png[1] == 'P' ? ByteBuffer.wrap(png).getInt(16) : -1;
    }

    /** The data of the .icns file's largest PNG entry (entries: 4-byte type, u32 length, data; big-endian). */
    private static byte[] largestIcnsPng(byte[] icns, File icon) throws IOException {
        var b = ByteBuffer.wrap(icns);
        byte[] best = null;
        for (var at = 8; at + 8 <= icns.length; ) {
            var len = b.getInt(at + 4);
            if (len < 8 || at + len > icns.length) break;
            var data = java.util.Arrays.copyOfRange(icns, at + 8, at + len);
            if (pngSize(data) > 0 && (best == null || pngSize(data) > pngSize(best))) best = data;
            at += len;
        }
        if (best == null) throw new IOException(icon + " has no PNG entry; give macosIcon a .png instead");
        return best;
    }

    /** The PNG data of the .ico's largest PNG entry. */
    private static byte[] largestIcoPng(byte[] ico, File icon) throws IOException {
        var b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        var count = b.getShort(4) & 0xFFFF;
        byte[] best = null;
        var bestSize = -1;
        for (var i = 0; i < count; i++) {
            var e = 6 + i * 16;
            var w = ico[e] & 0xFF;
            var size = w == 0 ? 256 : w;
            var len = b.getInt(e + 8);
            var off = b.getInt(e + 12);
            var isPng = len > 8 && (ico[off] & 0xFF) == 0x89 && ico[off + 1] == 'P' && ico[off + 2] == 'N' && ico[off + 3] == 'G';
            if (isPng && size > bestSize) {
                best = java.util.Arrays.copyOfRange(ico, off, off + len);
                bestSize = size;
            }
        }
        if (best == null) throw new IOException(icon + " has no PNG entry; give macosIcon a .png or .icns instead");
        return best;
    }

    /** Halves step by step down to the target: a single big bicubic step loses detail in a small icon. */
    private static BufferedImage scale(BufferedImage src, int size) {
        var img = src;
        var w = img.getWidth();
        while (w > size) {
            w = Math.max(size, w / 2);
            var next = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            var g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, w, w, null);
            g.dispose();
            img = next;
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
