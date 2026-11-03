package jarrunner.jr.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

/** PRP-61 (the old PRP-57): document types and imported types in the Info.plist. */
class PlistDocsTest {

    @TempDir Path dir;

    static DocumentType markdown() {
        var d = new DocumentType();
        d.name = "Markdown document";
        d.role = "Editor";
        d.rank = "Default";
        d.utis = List.of("net.daringfireball.markdown");
        d.extensions = List.of(".md", "markdown");
        return d;
    }

    static ImportedType notes() {
        var t = new ImportedType();
        t.identifier = "com.example.notes.note";
        t.description = "Note & outline";
        t.extensions = List.of("note");
        return t;
    }

    /** The plist's XML as a DOM, without fetching Apple's DTD. */
    static Document parse(String plist) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(plist.getBytes("UTF-8")));
    }

    @Test void aDocumentTypeIsWrittenAsAnEntryOfCFBundleDocumentTypes() throws Exception {
        var x = PlistDocs.of(List.of(markdown()), null);
        parse("<plist><dict>" + x + "</dict></plist>");   // well formed
        assertTrue(x.contains("<key>CFBundleDocumentTypes</key>") && x.contains("<string>Markdown document</string>"), x);
        assertTrue(x.contains("<key>CFBundleTypeRole</key>\n      <string>Editor</string>") && x.contains("<key>LSHandlerRank</key>\n      <string>Default</string>"), x);
        assertTrue(x.contains("<key>LSItemContentTypes</key>") && x.contains("<string>net.daringfireball.markdown</string>"), x);
        assertTrue(x.contains("<string>md</string>") && !x.contains("<string>.md</string>"), "a leading dot is dropped: " + x);
    }

    @Test void anImportedTypeNamesAnExtensionMacOsHasNoTypeFor() throws Exception {
        var x = PlistDocs.of(null, List.of(notes()));
        parse("<plist><dict>" + x + "</dict></plist>");
        assertTrue(x.contains("<key>UTImportedTypeDeclarations</key>") && x.contains("<string>com.example.notes.note</string>"), x);
        assertTrue(x.contains("<string>Note &amp; outline</string>") && x.contains("<string>public.data</string>"), x);
        assertTrue(x.contains("<key>public.filename-extension</key>") && x.contains("<string>note</string>"), x);
    }

    @Test void nothingDeclaredWritesNothing() throws Exception {
        assertEquals("", PlistDocs.of(null, null));
        assertEquals("", PlistDocs.of(List.of(), List.of()));
    }

    @Test void aMistakeStopsTheBuildAndSaysWhere() {
        var noName = markdown();
        noName.name = " ";
        assertTrue(assertThrows(MojoExecutionException.class, () -> PlistDocs.of(List.of(noName), null)).getMessage().contains("name"));
        var nothing = markdown();
        nothing.utis = null;
        nothing.extensions = List.of();
        assertTrue(assertThrows(MojoExecutionException.class, () -> PlistDocs.of(List.of(nothing), null)).getMessage().contains("needs utis or extensions"));
        var badRole = markdown();
        badRole.role = "Owner";
        assertTrue(assertThrows(MojoExecutionException.class, () -> PlistDocs.of(List.of(badRole), null)).getMessage().contains("role"));
        var noExt = notes();
        noExt.extensions = null;
        assertTrue(assertThrows(MojoExecutionException.class, () -> PlistDocs.of(null, List.of(noExt))).getMessage().contains("needs extensions"));
    }

    /** Through the mojo: the app's Info.plist and the one in the binary carry the types, and the plist is well formed. */
    @Test void bothFormsCarryTheTypes() throws Exception {
        var m = new ExeMojo();
        m.jar = TestApp.jar(dir);
        m.name = "hello";
        m.appId = "io.github.example:hello";
        m.appVersion = "1.0";
        m.source = "path";
        m.aot = false;
        m.outputDirectory = dir.resolve("out").toFile();
        m.platforms = List.of("macos");
        m.macosForms = List.of("app", "binary");
        m.macosArchitecture = "arm64";
        m.macosDocumentTypes = List.of(markdown());
        m.macosImportedTypes = List.of(notes());
        m.execute();
        var plist = Files.readString(dir.resolve("out/macos/hello.app/Contents/Info.plist"));
        var doc = parse(plist);
        assertTrue(doc.getElementsByTagName("key").getLength() > 0);
        assertEquals(1, plist.split("<key>CFBundleDocumentTypes</key>", -1).length - 1, plist);
        assertTrue(plist.contains("net.daringfireball.markdown") && plist.contains("com.example.notes.note"), plist);
        var bin = new File(dir.toFile(), "out/macos/hello");
        assertTrue(new String(Files.readAllBytes(bin.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1).contains("net.daringfireball.markdown"),
                "the binary carries the same plist, so -Xjr:install writes it");
    }
}
