package jarrunner.jr.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PRP-61: libraries declare what they need, the app adds its own, and what the jar suggests that nobody declared is
 *  reported (stopping a release build, warning otherwise). Nothing is required of an app where nothing is suspected. */
class MacPermissionsTest {

    @TempDir Path dir;

    static final String MIC = "Dictation records what you say and turns it into text.";
    static final String RECORDS = "javax/sound/sampled/TargetDataLine";
    static final String LIBRARY_DECLARATION = "{\"permissions\":{\"NSMicrophoneUsageDescription\":\"" + MIC + "\"}}";

    /** A jar with one class file whose bytes name the given strings (as a real class's constant pool does), and the
     *  given library declaration files, name to json. */
    File jar(Map<String, String> declarations, String... names) throws Exception {
        var jar = dir.resolve("app.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("app/Main.class"));
            out.write(("Êþº¾ " + String.join(" ", names)).getBytes("ISO-8859-1"));
            out.closeEntry();
            for (var d : declarations.entrySet()) {
                out.putNextEntry(new JarEntry(PermissionScan.LIBRARY_DIR + d.getKey() + ".json"));
                out.write(d.getValue().getBytes("UTF-8"));
                out.closeEntry();
            }
        }
        return jar.toFile();
    }

    MacPermissions of(Map<String, String> pom, Map<String, String> notNeeded, File jar) throws Exception {
        return MacPermissions.of(pom, notNeeded, null, PermissionScan.of(jar));
    }

    @Test void anAppWhereNothingIsSuspectedNeedsNothingInThePom() throws Exception {
        var m = of(null, null, jar(Map.of()));
        m.enforce(new SystemStreamLog(), "auto", true);
        assertTrue(m.problems.isEmpty() && m.plist().isEmpty());
        assertTrue(m.report("Hello", "io.example.hello").contains("Nothing that macOS protects was declared or suspected."));
    }

    @Test void aLibrarysDeclarationReachesTheAppWithoutTheAppRememberingAnything() throws Exception {
        var m = of(null, null, jar(Map.of("example-dictation", LIBRARY_DECLARATION), RECORDS));
        m.enforce(new SystemStreamLog(), "auto", true);   // a release build, and still fine
        assertEquals(Map.of("NSMicrophoneUsageDescription", MIC), m.plist());
        assertTrue(m.report("Notes", "io.example.notes").contains("from library example-dictation"));
    }

    @Test void theAppMayRewordALibrarysSentence() throws Exception {
        var mine = "The notes app listens while you dictate into a note.";
        var m = of(Map.of("microphone", mine), null, jar(Map.of("example-dictation", LIBRARY_DECLARATION), RECORDS));
        assertEquals(Map.of("NSMicrophoneUsageDescription", mine), m.plist());
        assertEquals("the pom", m.from.get("NSMicrophoneUsageDescription"));
    }

    @Test void theScanIsTheSafetyNetForALibraryThatSaysNothing() throws Exception {
        var m = of(null, null, jar(Map.of(), RECORDS));
        assertEquals(1, m.problems.size());
        var p = m.problems.get(0);
        assertTrue(p.contains("the microphone") && p.contains("app/Main.class") && p.contains("<microphone>") && p.contains("macosPermissionsNotNeeded"), p);
        m.enforce(new SystemStreamLog(), "auto", false);   // a development build only warns
        var e = assertThrows(MojoExecutionException.class, () -> m.enforce(new SystemStreamLog(), "auto", true));   // a release stops
        assertTrue(e.getMessage().contains("seems to use the microphone"), e.getMessage());
        assertThrows(MojoExecutionException.class, () -> m.enforce(new SystemStreamLog(), "fail", false));
        m.enforce(new SystemStreamLog(), "warn", true);
        assertThrows(MojoExecutionException.class, () -> m.enforce(new SystemStreamLog(), "stop", true));
    }

    @Test void declaringItOrSayingWhyNotSilencesTheSuspicion() throws Exception {
        var j = jar(Map.of(), RECORDS);
        of(Map.of("microphone", MIC), null, j).enforce(new SystemStreamLog(), "auto", true);
        var no = of(null, Map.of("microphone", "The recording code in the library is never called."), j);
        no.enforce(new SystemStreamLog(), "auto", true);
        assertTrue(no.plist().isEmpty());
        assertTrue(no.report("Hello", "io.example.hello").contains("declared not needed: The recording code in the library is never called."));
        var bare = of(null, Map.of("microphone", "no"), j);
        assertTrue(bare.problems.get(0).contains("needs the reason"), bare.problems.toString());
    }

    @Test void anyPlistKeyIsAllowedNotOnlyTheKnownNames() throws Exception {
        var m = of(new LinkedHashMap<>(Map.of("NSCalendarsFullAccessUsageDescription", "Shows your meetings beside the notes you take.")), null, jar(Map.of()));
        assertEquals(List.of("NSCalendarsFullAccessUsageDescription"), List.copyOf(m.plist().keySet()));
        assertTrue(m.problems.isEmpty(), m.problems.toString());
        var typo = of(Map.of("micophone", MIC), null, jar(Map.of()));
        assertTrue(typo.problems.get(0).contains("neither a known name nor an Info.plist key"), typo.problems.toString());
    }

    @Test void aUsageKeyAlreadyInMacosInfoPlistCountsAsDeclared() throws Exception {
        var m = MacPermissions.of(null, null, Map.of("NSMicrophoneUsageDescription", MIC), PermissionScan.of(jar(Map.of(), RECORDS)));
        m.enforce(new SystemStreamLog(), "auto", true);
        assertTrue(m.problems.isEmpty(), m.problems.toString());
    }

    @Test void pathStringsAreOnlyHintsAndNeverStopAnything() throws Exception {
        var m = of(null, null, jar(Map.of(), "/Documents", "/Downloads"));
        assertTrue(m.problems.isEmpty());
        assertEquals(2, m.hints.size());
        m.enforce(new SystemStreamLog(), "fail", true);
        assertTrue(of(Map.of("documents", "Opens and saves the files you choose in Documents.", "downloads", "Saves what you export into your Downloads folder."),
                null, jar(Map.of(), "/Documents", "/Downloads")).hints.isEmpty());
    }

    @Test void aPermissionWithNoPromptGoesOnlyIntoTheReport() throws Exception {
        var m = of(new LinkedHashMap<>(Map.of("screenRecording", "It shows the screen to the person helping remotely.")), null, jar(Map.of(), "createScreenCapture"));
        m.enforce(new SystemStreamLog(), "fail", true);
        assertTrue(m.plist().isEmpty());
        var r = m.report("Hello", "io.example.hello");
        assertTrue(r.contains("Screen Recording") && r.contains("System Settings") && r.contains("tccutil reset All io.example.hello") && r.contains("never from a terminal"), r);
    }

    /** End to end through the mojo: a development build warns and builds, a release build stops, and a library's
     *  declaration puts the key into the Info.plist with a report beside it. */
    @Test void theMojoWarnsOnADevelopmentBuildStopsARelease() throws Exception {
        var m = new ExeMojo();
        m.jar = jar(Map.of(), RECORDS);
        m.name = "hello";
        m.appId = "io.github.example:hello";
        m.appVersion = "1.0";
        m.source = "path";
        m.aot = false;
        m.outputDirectory = dir.resolve("out").toFile();
        m.platforms = List.of("macos");
        m.macosForms = List.of("app");
        m.macosArchitecture = "arm64";
        m.execute();   // warns, builds
        assertFalse(Files.readString(dir.resolve("out/macos/hello.app/Contents/Info.plist")).contains("NSMicrophoneUsageDescription"));
        m.release = true;
        m.source = "path";
        m.releaseBaseUrl = "https://example.com/r/";
        m.releaseDirectory = dir.resolve("out/release").toFile();
        var e = assertThrows(MojoExecutionException.class, m::execute);
        assertTrue(e.getMessage().contains("seems to use the microphone"), e.getMessage());
        m.jar = jar(Map.of("example-dictation", LIBRARY_DECLARATION), RECORDS);
        m.execute();
        var plist = Files.readString(dir.resolve("out/macos/hello.app/Contents/Info.plist"));
        assertTrue(plist.contains("<key>NSMicrophoneUsageDescription</key>") && plist.contains(MIC), plist);
        assertTrue(Files.readString(dir.resolve("out/macos/hello.permissions.txt")).contains("library example-dictation"));
    }
}
