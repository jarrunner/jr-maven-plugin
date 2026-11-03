package jarrunner.jr.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/** PRP-61: what the app's jar says about the permissions it needs, in two ways.
 *  <ul>
 *  <li>{@code libraries}: what a library in the jar DECLARES. A library that uses the microphone ships
 *      {@code META-INF/jarrunner/macos-permissions/<name>.json}, {"permissions": {"NSMicrophoneUsageDescription":
 *      "the sentence the person reads"}}, so the app that uses the library cannot forget (the way a Cordova plugin
 *      or an Expo config plugin brings its own Info.plist key). Key: the plist key (or an alias), value: the text.</li>
 *  <li>{@code strong} / {@code weak}: what the classes SUGGEST, the safety net for a library that says nothing, as
 *      Apple's own submission check is. A class names every class it uses, and every string it holds, as plain text
 *      inside the class file, so a search of the raw bytes finds a use of javax.sound's TargetDataLine without
 *      parsing anything. Library code that is never called is found too: that costs one line in the pom.</li>
 *  </ul> */
final class PermissionScan {
    static final String LIBRARY_DIR = "META-INF/jarrunner/macos-permissions/";
    final Map<MacPermission, String> strong = new EnumMap<>(MacPermission.class);
    final Map<MacPermission, String> weak = new EnumMap<>(MacPermission.class);
    /** plist key (or alias) to text, then where it came from, in the order found; the first library to say a key wins. */
    final Map<String, String> libraries = new LinkedHashMap<>();
    final Map<String, String> libraryOf = new LinkedHashMap<>();

    static final PermissionScan NOTHING = new PermissionScan();

    /** A path hint is a weak signal, so it must be a whole path component: /Documents, not /DocumentsWriter (a Lucene
     *  class), and not java/awt/Desktop (the class that opens files, which needs nothing). */
    static boolean hinted(String text, MacPermission p) {
        var t = text.replace("java/awt/Desktop", "");
        return p.weak.stream().anyMatch(h -> java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(h) + "(?![A-Za-z0-9_$])").matcher(t).find());
    }

    static PermissionScan of(File jar) throws Exception {
        var s = new PermissionScan();
        var json = new ObjectMapper();
        try (var zip = new ZipFile(jar)) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var e = entries.nextElement();
                var name = e.getName();
                byte[] bytes;
                if (name.endsWith(".class") || name.startsWith(LIBRARY_DIR) && name.endsWith(".json")) {
                    try (var in = zip.getInputStream(e)) {
                        bytes = in.readAllBytes();
                    }
                } else continue;
                if (name.endsWith(".json")) {
                    var from = name.substring(LIBRARY_DIR.length(), name.length() - ".json".length());
                    json.readTree(bytes).path("permissions").fields().forEachRemaining(f -> {
                        s.libraries.putIfAbsent(f.getKey(), f.getValue().asText());
                        s.libraryOf.putIfAbsent(f.getKey(), from);
                    });
                    continue;
                }
                var text = new String(bytes, StandardCharsets.ISO_8859_1);
                for (var p : MacPermission.values()) {
                    if (!s.strong.containsKey(p) && p.strong.stream().anyMatch(text::contains)) s.strong.put(p, name);
                    if (!s.weak.containsKey(p) && hinted(text, p)) s.weak.put(p, name);
                }
            }
        }
        return s;
    }
}
