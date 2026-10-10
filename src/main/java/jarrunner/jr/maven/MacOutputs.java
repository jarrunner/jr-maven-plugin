package jarrunner.jr.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/** The macOS outputs of jr:exe (PRP-36), built on any OS from the jr-macos binaries bundled in this plugin.
 *  <ul>
 *  <li>binary: one file, {@code macos/<name>}, with the app's config embedded (MachOStamper). Copies and installs
 *      like any command-line tool. No icon: macOS gives icons to bundles, not to bare binaries.</li>
 *  <li>app: {@code macos/<Display Name>.app} (Info.plist, the same embedded binary in Contents/MacOS, the icon in
 *      Contents/Resources) and {@code macos/<name>-<version>-macos.zip} of it, the single file to download.</li>
 *  </ul>
 *  Architecture: arm64, x86_64 or universal (both in one fat binary). Every output is signed ad hoc only. */
final class MacOutputs {
    private final Log log;
    private final File dir;
    private final String name;
    String displayName;
    String bundleId;
    String version;
    String minimumSystemVersion = "13.0";
    File icon;
    final Map<String, String> extraPlist = new LinkedHashMap<>();

    MacOutputs(Log log, File outputDirectory, String name) {
        this.log = log;
        this.dir = new File(outputDirectory, "macos");
        this.name = name;
    }

    /** The jr-macos binary for the architecture, from this plugin's own resources. */
    static byte[] jr(String arch) throws MojoExecutionException {
        var res = switch (arch) {
            case "universal" -> "jr-macos";
            case "arm64", "x86_64" -> "jr-macos-" + arch;
            default -> throw new MojoExecutionException("macosArchitecture must be arm64, x86_64 or universal, not " + arch);
        };
        try (InputStream in = MacOutputs.class.getResourceAsStream(res)) {
            if (in == null) {
                throw new MojoExecutionException("this jr-maven-plugin build carries no " + res
                        + " (its bundled macOS jr binaries are built on a Mac by jr's mvn package)");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new MojoExecutionException("could not read the bundled " + res, e);
        }
    }

    /** The update file's platform key for an architecture (PRP-42): macos-universal, macos-arm64 or macos-x86_64, the
     *  keys jr's -Xjr:update tries on a Mac (universal first, then the Mac's own architecture). */
    static String platform(String arch) {
        return "macos-" + arch;
    }

    /** jr for arch with the config and, after it, the app's bundle files (PRP-42), so the binary can write its own
     *  .app (jr -Xjr:install) and rewrite it after an update. The icon goes in as one 512 px PNG if it fits, else 256 px,
     *  else not at all; the Info.plist names it either way, so it is the same Info.plist the app form writes. */
    private byte[] stamped(String arch, String config) throws Exception {
        var jr = jr(arch);
        var room = MachOStamper.capacity(jr) - MachOStamper.afterOffset(config.getBytes(StandardCharsets.UTF_8).length) - 1;
        var plist = infoPlist(icon == null ? null : name + ".icns");
        for (var size : icon == null ? new int[] {0} : new int[] {512, 256, 0}) {
            var files = bundleFiles(plist, size == 0 ? null : IcnsWriter.single(icon, size));
            if (files.length <= room) {
                if (icon != null && size != 512) {
                    log.warn("macOS: the icon is " + (size == 0 ? "left out of" : "256 px in") + " the binary, to fit its "
                            + MachOStamper.capacity(jr) / 1024 + " KB section (a smaller PNG, such as a 512 px palette PNG, fits at 512)");
                }
                return MachOStamper.stamp(jr, config, files);
            }
        }
        log.warn("macOS: the app's bundle files do not fit beside the config, so this binary cannot install its own .app");
        return MachOStamper.stamp(jr, config);
    }

    /** The section layout jr's AppBundle reads: "jrapp1\0\0", the bundle's folder name, then (path, data) entries
     *  relative to the bundle, ending with a zero length; every length a little-endian u32. */
    private byte[] bundleFiles(String plist, byte[] icns) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        out.write("jrapp1\0\0".getBytes(StandardCharsets.US_ASCII));
        var bundle = (displayName + ".app").getBytes(StandardCharsets.UTF_8);
        out.write(u32(bundle.length));
        out.write(bundle);
        bundleEntry(out, "Contents/Info.plist", plist.getBytes(StandardCharsets.UTF_8));
        bundleEntry(out, "Contents/PkgInfo", "APPL????".getBytes(StandardCharsets.US_ASCII));
        if (icns != null) bundleEntry(out, "Contents/Resources/" + name + ".icns", icns);
        out.write(u32(0));
        return out.toByteArray();
    }

    private static void bundleEntry(java.io.ByteArrayOutputStream out, String path, byte[] data) throws IOException {
        var p = path.getBytes(StandardCharsets.UTF_8);
        out.write(u32(p.length));
        out.write(p);
        out.write(u32(data.length));
        out.write(data);
    }

    private static byte[] u32(int v) {
        return java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    /** The plain binary; returns it. */
    File binary(String arch, String config) throws Exception {
        dir.mkdirs();
        var out = new File(dir, name);
        Files.write(out.toPath(), stamped(arch, config));
        executable(out.toPath());
        log.info("macOS binary (" + arch + "): " + out);
        return out;
    }

    /** The .app and its zip; returns the zip. The app carries no jar: its config names the jar as on Windows (the
     *  build's own path, or a maven/url download into jr's cache), so a new jar needs no new bundle. */
    File app(String arch, String config) throws Exception {
        dir.mkdirs();
        var app = new File(dir, displayName + ".app");
        deleteTree(app.toPath());
        var macos = new File(app, "Contents/MacOS");
        var resources = new File(app, "Contents/Resources");
        macos.mkdirs();
        resources.mkdirs();
        var exe = new File(macos, name);
        Files.write(exe.toPath(), stamped(arch, config));
        executable(exe.toPath());
        String iconFile = null;
        if (icon != null) {
            iconFile = name + ".icns";
            Files.write(new File(resources, iconFile).toPath(), IcnsWriter.from(icon));
        }
        Files.writeString(new File(app, "Contents/Info.plist").toPath(), infoPlist(iconFile));
        Files.writeString(new File(app, "Contents/PkgInfo").toPath(), "APPL????");
        sealOnMac(app);
        var zip = new File(dir, name + "-" + version + "-macos.zip");
        zip(app.toPath(), zip);
        log.info("macOS app (" + arch + (icon == null ? ", no icon" : "") + "): " + app);
        log.info("macOS app zip: " + zip);
        return zip;
    }

    /** Inside an .app macOS expects a bundle signature that also seals Info.plist and Contents/Resources; the binary's
     *  own ad-hoc signature does not. The app runs either way (measured on an M3, from a zip built on Windows), but
     *  `codesign -v` then reports the bundle as unsealed. Built on a Mac, Apple's codesign seals it ad hoc; elsewhere
     *  there is no codesign and the app ships as it is. */
    private void sealOnMac(File app) throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Mac")) return;
        var p = new ProcessBuilder("codesign", "--force", "-s", "-", app.getPath()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new MojoExecutionException("codesign could not seal " + app + ":\n" + out);
        log.info("sealed ad hoc with codesign: " + app.getName());
    }

    /** jr runs the app in a child java process; inside a bundle jr itself passes -Xdock:name/-Xdock:icon so the Dock
     *  shows this name and icon rather than "java" (see the macOS Os.bundleVmArgs). */
    private String infoPlist(String iconFile) {
        var b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        b.append("<plist version=\"1.0\">\n<dict>\n");
        entry(b, "CFBundleDevelopmentRegion", "en");
        entry(b, "CFBundleExecutable", name);
        entry(b, "CFBundleIdentifier", bundleId);
        entry(b, "CFBundleInfoDictionaryVersion", "6.0");
        entry(b, "CFBundleName", displayName);
        entry(b, "CFBundleDisplayName", displayName);
        entry(b, "CFBundlePackageType", "APPL");
        entry(b, "CFBundleShortVersionString", shortVersion(version));
        entry(b, "CFBundleVersion", version);
        if (iconFile != null) entry(b, "CFBundleIconFile", iconFile);
        entry(b, "LSMinimumSystemVersion", minimumSystemVersion);
        // PRP-11: without this, an Apple-silicon Mac may start the bundle under Rosetta, and everything it starts too
        b.append("  <key>LSArchitecturePriority</key>\n  <array>\n    <string>arm64</string>\n    <string>x86_64</string>\n  </array>\n");
        if (!extraPlist.containsKey("NSHighResolutionCapable")) b.append("  <key>NSHighResolutionCapable</key>\n  <true/>\n");
        extraPlist.forEach((k, v) -> {
            b.append("  <key>").append(xml(k)).append("</key>\n");
            b.append(v.equals("true") || v.equals("false") ? "  <" + v + "/>\n" : "  <string>" + xml(v) + "</string>\n");
        });
        b.append("</dict>\n</plist>\n");
        return b.toString();
    }

    /** A built-in string entry, unless the build supplied the same key in macosInfoPlist. */
    private void entry(StringBuilder b, String key, String value) {
        if (extraPlist.containsKey(key)) return;
        b.append("  <key>").append(key).append("</key>\n  <string>").append(xml(value)).append("</string>\n");
    }

    /** CFBundleShortVersionString is up to three dot-separated numbers: 1.2.0-SNAPSHOT becomes 1.2.0. */
    static String shortVersion(String v) {
        var end = 0;
        while (end < v.length() && (Character.isDigit(v.charAt(end)) || v.charAt(end) == '.')) end++;
        var s = v.substring(0, end);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "0" : s;
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A zip that keeps the executable bit (unix mode in each entry), which java.util.zip cannot write: without it,
     *  the app unzipped on a Mac would not start. */
    private static void zip(Path app, File zip) throws IOException {
        var base = app.getParent();
        try (var out = new ZipArchiveOutputStream(zip); var walk = Files.walk(app)) {
            for (var p : (Iterable<Path>) walk.sorted()::iterator) {
                var rel = base.relativize(p).toString().replace('\\', '/');
                var isDir = Files.isDirectory(p);
                var e = new ZipArchiveEntry(isDir ? rel + "/" : rel);
                e.setUnixMode(isDir ? 040755 : (p.getParent().endsWith("MacOS") ? 0100755 : 0100644));
                e.setTime(Files.getLastModifiedTime(p).toMillis());
                out.putArchiveEntry(e);
                if (!isDir) Files.copy(p, out);
                out.closeArchiveEntry();
            }
        }
    }

    private static void executable(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: no POSIX permissions on the file; the zip carries the mode, and install.sh-style copies chmod
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            var all = new ArrayList<Path>();
            walk.forEach(all::add);
            for (var i = all.size() - 1; i >= 0; i--) Files.delete(all.get(i));
        }
    }

    static List<String> forms(List<String> forms) throws MojoExecutionException {
        var f = forms == null || forms.isEmpty() ? List.of("binary") : forms;
        for (var s : f) {
            if (!s.equals("binary") && !s.equals("app")) throw new MojoExecutionException("macosForms takes binary and/or app, not " + s);
        }
        return f;
    }
}
