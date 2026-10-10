package jarrunner.jr.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PRP-36: the macOS outputs. Needs the bundled jr-macos binaries (downloaded from the jr release, see the pom). */
class MacOutputsTest {

    @TempDir Path dir;

    ExeMojo mojo(File jar) {
        var m = new ExeMojo();
        m.jar = jar;
        m.name = "hello";
        m.appId = "io.github.example:hello";
        m.appVersion = "1.2.3-SNAPSHOT";
        m.source = "path";
        m.outputDirectory = dir.resolve("out").toFile();
        m.aot = false;
        m.platforms = List.of("macos");
        return m;
    }

    @ParameterizedTest
    @ValueSource(strings = {"arm64", "x86_64", "universal"})
    void stampedConfigReadsBackAndEveryPageHashStillMatches(String arch) throws Exception {
        var config = "{\"app\":{\"name\":\"hello\"},\"text\":\"é日本🙂\"}";
        var out = MachOStamper.stamp(MacOutputs.jr(arch), config);
        assertEquals(config, MachOStamper.read(out));
        assertEquals(arch.equals("universal") ? 2 : 1, MachOStamper.slices(out).size());
        verifySignatures(out);
    }

    @Test void restampingReplacesTheOldConfigCompletely() throws Exception {
        var once = MachOStamper.stamp(MacOutputs.jr("arm64"), "x".repeat(5000));
        var twice = MachOStamper.stamp(once, "{}");
        assertEquals("{}", MachOStamper.read(twice));
        verifySignatures(twice);
    }

    /** PRP-42: a release lists the macOS binary under exe and the app's zip under app, so -Xjr:update finds them. */
    @Test void releaseListsTheBinaryAndTheAppZipInTheUpdateFile() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.source = "url";
        m.jarUrl = "https://github.com/example/hello/releases/download/v1.2.3/hello.jar";
        m.releaseDirectory = dir.resolve("out/release").toFile();
        m.macosForms = List.of("binary", "app");
        m.execute();
        var rel = m.releaseDirectory.toPath();
        var u = ExeMojo.mapper().readTree(rel.resolve("hello.update.json").toFile()).path("releases").get(0);
        var bin = u.path("exe").path("macos-universal");
        assertEquals(ReleaseWriter.sha256(rel.resolve("hello").toFile()), bin.path("sha256").asText());
        assertEquals("https://github.com/example/hello/releases/download/v1.2.3/hello", bin.path("urls").get(0).asText());
        var app = u.path("app").path("macos-universal");
        assertEquals(ReleaseWriter.sha256(rel.resolve("hello-1.2.3-SNAPSHOT-macos.zip").toFile()), app.path("sha256").asText());
        assertTrue(app.path("urls").get(0).asText().endsWith("/hello-1.2.3-SNAPSHOT-macos.zip"), app.toString());
        var sums = Files.readString(rel.resolve("SHA256SUMS"));
        assertTrue(sums.contains("  hello\n") && sums.contains("  hello-1.2.3-SNAPSHOT-macos.zip\n"), sums);
    }

    /** PRP-42: the binary carries its app's bundle files after the config, in the layout jr's AppBundle reads, and
     *  their Info.plist is byte for byte the one the app form writes (so jr leaves a plugin-built bundle alone). */
    @Test void theBinaryCarriesItsAppsBundleFiles() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.macosArchitecture = "arm64";
        m.icon = new File("src/test/resources/jr-icon.ico");
        m.productName = "Hello World";
        m.macosForms = List.of("binary", "app");
        m.execute();
        var sect = MachOStamper.sectionBytes(Files.readAllBytes(dir.resolve("out/macos/hello")));
        var le = ByteBuffer.wrap(sect).order(ByteOrder.LITTLE_ENDIAN);
        var end = 0;
        while (sect[end] != 0) end++;
        var at = MachOStamper.afterOffset(end);
        assertEquals("jrapp1\0\0", new String(sect, at, 8, java.nio.charset.StandardCharsets.US_ASCII));
        at += 8;
        var files = new java.util.LinkedHashMap<String, byte[]>();
        var n = le.getInt(at);
        assertEquals("Hello World.app", new String(sect, at + 4, n, java.nio.charset.StandardCharsets.UTF_8));
        at += 4 + n;
        while ((n = le.getInt(at)) != 0) {
            var path = new String(sect, at + 4, n, java.nio.charset.StandardCharsets.UTF_8);
            at += 4 + n;
            var len = le.getInt(at);
            files.put(path, java.util.Arrays.copyOfRange(sect, at + 4, at + 4 + len));
            at += 4 + len;
        }
        assertEquals(List.of("Contents/Info.plist", "Contents/PkgInfo", "Contents/Resources/hello.icns"), List.copyOf(files.keySet()));
        var app = dir.resolve("out/macos/Hello World.app/Contents");
        assertArrayEquals(Files.readAllBytes(app.resolve("Info.plist")), files.get("Contents/Info.plist"));
        var icns = files.get("Contents/Resources/hello.icns");
        assertEquals("icns", new String(icns, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(icns.length, ByteBuffer.wrap(icns).getInt(4));
        assertEquals(1, icnsEntries(icns), "one image, scaled by macOS");
        System.out.println("embedded icns: " + icns.length + " bytes, type " + new String(icns, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** Not a check: writes a real section (config + bundle files) to -Djr.slotOut for jr's own tests of AppBundle on
     *  Linux (prp/42-prp.03.install-test.sh). Skipped unless the property is set. */
    @Test void dumpSectionForJrTests() throws Exception {
        var outFile = System.getProperty("jr.slotOut");
        org.junit.jupiter.api.Assumptions.assumeTrue(outFile != null);
        var m = mojo(TestApp.jar(dir));
        m.macosArchitecture = "arm64";
        m.icon = new File("src/test/resources/jr-icon.ico");
        m.productName = "Hello World";
        m.appVersion = System.getProperty("jr.slotVersion", "1.0");
        m.javaArgs = "-version";
        m.updateUrl = "https://localhost:8443/app.update.json";
        m.macosForms = List.of("binary");
        m.execute();
        Files.write(Path.of(outFile), MachOStamper.sectionBytes(Files.readAllBytes(dir.resolve("out/macos/hello"))));
    }

    private static int icnsEntries(byte[] icns) {
        var count = 0;
        for (var at = 8; at + 8 <= icns.length; at += ByteBuffer.wrap(icns).getInt(at + 4)) count++;
        return count;
    }

    @Test void aConfigTooBigForTheSectionIsRefused() {
        var e = assertThrows(java.io.IOException.class, () -> MachOStamper.stamp(MacOutputs.jr("arm64"), "x".repeat(20000)));
        assertTrue(e.getMessage().contains("holds"), e.getMessage());
    }

    @Test void binaryFormIsOneFileWithItsConfigInside() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.macosArchitecture = "arm64";
        m.execute();
        var bin = dir.resolve("out/macos/hello");
        assertTrue(Files.isRegularFile(bin));
        var cfg = MachOStamper.read(Files.readAllBytes(bin));
        assertTrue(cfg.contains("\"io.github.example:hello\""), cfg);
        // built off a Mac with source=path: the jar is expected beside the binary
        assertTrue(cfg.contains("\"path\":\"hello.jar\""), cfg);
        assertFalse(Files.exists(dir.resolve("out/hello.exe")), "platforms=macos makes no Windows exe");
        verifySignatures(Files.readAllBytes(bin));
    }

    @Test void extraInfoPlistEntriesAreAddedAndReplaceBuiltIns() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.macosForms = List.of("app");
        m.macosNoIcon = true;
        m.macosInfoPlist = new java.util.LinkedHashMap<>(java.util.Map.of(
                "NSMicrophoneUsageDescription", "Voice Typing listens & transcribes", "LSUIElement", "true",
                "LSMinimumSystemVersion", "14.0"));
        m.execute();
        var plist = Files.readString(dir.resolve("out/macos/hello.app/Contents/Info.plist"));
        assertTrue(plist.contains("<key>NSMicrophoneUsageDescription</key>\n  <string>Voice Typing listens &amp; transcribes</string>"), plist);
        assertTrue(plist.contains("<key>LSUIElement</key>\n  <true/>"), plist);
        assertTrue(plist.contains("<string>14.0</string>") && !plist.contains("<string>13.0</string>"), plist);
        assertEquals(1, plist.split("<key>LSMinimumSystemVersion</key>", -1).length - 1, plist);
    }

    @Test void appFormWithIconCarriesThePlistAndAnIcnsButNoJarAndZipsWithModes() throws Exception {
        var jar = TestApp.jar(dir);
        var m = mojo(jar);
        m.macosForms = List.of("app");
        m.icon = new File("src/test/resources/jr-icon.ico");
        m.productName = "Hello World";
        m.execute();
        var app = dir.resolve("out/macos/Hello World.app/Contents");
        var cfg = MachOStamper.read(Files.readAllBytes(app.resolve("MacOS/hello")));
        // as on Windows: the build's own jar by path, never a copy inside the bundle (user, 2026-10-09)
        assertTrue(cfg.contains(jar.getAbsolutePath().replace("\\", "\\\\")), cfg);
        try (var s = Files.walk(dir.resolve("out/macos/Hello World.app"))) {
            assertTrue(s.noneMatch(p -> p.toString().endsWith(".jar")), "no jar in the bundle");
        }
        var plist = Files.readString(app.resolve("Info.plist"));
        assertTrue(plist.contains("<string>io.github.example.hello</string>"), plist);
        assertTrue(plist.contains("<key>CFBundleShortVersionString</key>\n  <string>1.2.3</string>"), plist);
        assertTrue(plist.contains("<string>hello.icns</string>"), plist);
        assertTrue(plist.contains("<string>arm64</string>"), plist);
        var icns = Files.readAllBytes(app.resolve("Resources/hello.icns"));
        assertEquals("icns", new String(icns, 0, 4));
        assertEquals(icns.length, ByteBuffer.wrap(icns).order(ByteOrder.BIG_ENDIAN).getInt(4));
        assertTrue(new String(icns, java.nio.charset.StandardCharsets.ISO_8859_1).contains("ic08"), "has the 256 px entry");
        try (var zip = ZipFile.builder().setFile(dir.resolve("out/macos/hello-1.2.3-SNAPSHOT-macos.zip").toFile()).get()) {
            assertEquals(0100755, zip.getEntry("Hello World.app/Contents/MacOS/hello").getUnixMode());
            assertEquals(0100644, zip.getEntry("Hello World.app/Contents/Info.plist").getUnixMode());
        }
    }

    @Test void appFormWithoutIcon() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.macosForms = List.of("app");
        m.icon = new File("src/test/resources/jr-icon.ico");
        m.macosNoIcon = true;
        m.macosArchitecture = "x86_64";
        m.execute();
        var app = dir.resolve("out/macos/hello.app/Contents");
        assertFalse(Files.readString(app.resolve("Info.plist")).contains("CFBundleIconFile"));
        try (var s = Files.list(app.resolve("Resources"))) {
            assertTrue(s.noneMatch(p -> p.toString().endsWith(".icns")));
        }
    }

    @Test void shortVersion() {
        assertEquals("1.2.3", MacOutputs.shortVersion("1.2.3-SNAPSHOT"));
        assertEquals("2.0", MacOutputs.shortVersion("2.0"));
        assertEquals("0", MacOutputs.shortVersion("beta"));
    }

    /** Recomputes every code page hash of every CodeDirectory independently of MachOStamper. */
    static void verifySignatures(byte[] b) throws Exception {
        for (var s : MachOStamper.slices(b)) {
            var base = s[0];
            var le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            var be = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            int at = base + 32, sig = -1;
            for (var i = 0; i < le.getInt(base + 16); i++) {
                if (le.getInt(at) == 0x1D) sig = base + le.getInt(at + 8);
                at += le.getInt(at + 4);
            }
            assertTrue(sig > 0, "signed");
            var checked = 0;
            for (var i = 0; i < be.getInt(sig + 8); i++) {
                var cd = sig + be.getInt(sig + 12 + i * 8 + 4);
                if (be.getInt(cd) != 0xFADE0C02) continue;
                var hashOffset = be.getInt(cd + 16);
                var slots = be.getInt(cd + 28);
                var limit = be.getInt(cd + 32);
                var size = b[cd + 36] & 0xFF;
                var page = 1 << (b[cd + 39] & 0xFF);
                var md = MessageDigest.getInstance((b[cd + 37] & 0xFF) == 1 ? "SHA-1" : "SHA-256");
                for (var p = 0; p < slots; p++) {
                    md.reset();
                    md.update(b, base + p * page, Math.min(page, limit - p * page));
                    var want = java.util.Arrays.copyOf(md.digest(), size);
                    var have = java.util.Arrays.copyOfRange(b, cd + hashOffset + p * size, cd + hashOffset + (p + 1) * size);
                    assertArrayEquals(want, have, "page " + p + " of the slice at " + base);
                }
                checked++;
            }
            assertTrue(checked > 0, "has a CodeDirectory");
        }
    }
}
