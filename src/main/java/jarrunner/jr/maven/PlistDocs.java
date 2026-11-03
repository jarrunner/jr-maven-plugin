package jarrunner.jr.maven;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;

/** PRP-61 (the old PRP-57): the Info.plist's arrays of dictionaries, which macosInfoPlist (strings only) cannot say. */
final class PlistDocs {
    private PlistDocs() {}

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void strings(StringBuilder b, String indent, String key, List<String> values) {
        b.append(indent).append("<key>").append(key).append("</key>\n").append(indent).append("<array>\n");
        values.forEach(v -> b.append(indent).append("  <string>").append(xml(v)).append("</string>\n"));
        b.append(indent).append("</array>\n");
    }

    private static void pair(StringBuilder b, String indent, String key, String value) {
        b.append(indent).append("<key>").append(key).append("</key>\n").append(indent).append("<string>").append(xml(value)).append("</string>\n");
    }

    private static String need(String v, String what) throws MojoExecutionException {
        if (v == null || v.isBlank()) throw new MojoExecutionException(what + " is needed");
        return v.strip();
    }

    private static List<String> clean(List<String> l) {
        return l == null ? List.of() : l.stream().map(String::strip).filter(s -> !s.isEmpty()).map(s -> s.startsWith(".") ? s.substring(1) : s).toList();
    }

    private static String oneOf(String v, String dflt, String what, String... allowed) throws MojoExecutionException {
        var x = v == null || v.isBlank() ? dflt : v.strip();
        if (!List.of(allowed).contains(x)) throw new MojoExecutionException(what + " must be one of " + String.join(", ", allowed) + ", not " + x);
        return x;
    }

    /** The xml of every entry's CFBundleDocumentTypes and UTImportedTypeDeclarations, ready to sit inside the plist's dict. */
    static String of(List<DocumentType> docs, List<ImportedType> imported) throws MojoExecutionException {
        var b = new StringBuilder();
        if (docs != null && !docs.isEmpty()) {
            b.append("  <key>CFBundleDocumentTypes</key>\n  <array>\n");
            for (var d : docs) {
                var name = need(d.name, "macosDocumentTypes: a documentType's name");
                var utis = clean(d.utis);
                var exts = clean(d.extensions);
                if (utis.isEmpty() && exts.isEmpty()) throw new MojoExecutionException("macosDocumentTypes: \"" + name + "\" needs utis or extensions");
                b.append("    <dict>\n");
                pair(b, "      ", "CFBundleTypeName", name);
                pair(b, "      ", "CFBundleTypeRole", oneOf(d.role, "Editor", "macosDocumentTypes: the role of \"" + name + "\"", "Editor", "Viewer", "Shell", "None"));
                pair(b, "      ", "LSHandlerRank", oneOf(d.rank, "Alternate", "macosDocumentTypes: the rank of \"" + name + "\"", "Alternate", "Default", "Owner", "None"));
                if (!utis.isEmpty()) strings(b, "      ", "LSItemContentTypes", utis);
                if (!exts.isEmpty()) strings(b, "      ", "CFBundleTypeExtensions", exts);
                b.append("    </dict>\n");
            }
            b.append("  </array>\n");
        }
        if (imported != null && !imported.isEmpty()) {
            b.append("  <key>UTImportedTypeDeclarations</key>\n  <array>\n");
            for (var t : imported) {
                var id = need(t.identifier, "macosImportedTypes: an importedType's identifier");
                var exts = clean(t.extensions);
                if (exts.isEmpty()) throw new MojoExecutionException("macosImportedTypes: \"" + id + "\" needs extensions");
                var conforms = new ArrayList<>(clean(t.conformsTo));
                if (conforms.isEmpty()) conforms.add("public.data");
                b.append("    <dict>\n");
                pair(b, "      ", "UTTypeIdentifier", id);
                pair(b, "      ", "UTTypeDescription", t.description == null || t.description.isBlank() ? id : t.description.strip());
                strings(b, "      ", "UTTypeConformsTo", conforms);
                b.append("      <key>UTTypeTagSpecification</key>\n      <dict>\n");
                strings(b, "        ", "public.filename-extension", exts);
                b.append("      </dict>\n    </dict>\n");
            }
            b.append("  </array>\n");
        }
        return b.toString();
    }
}
