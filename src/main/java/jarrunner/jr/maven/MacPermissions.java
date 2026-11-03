package jarrunner.jr.maven;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/** PRP-61: what a macOS app must tell macOS up front. On Windows an app just runs; on a Mac every protected thing
 *  (microphone, camera, the Documents folder, ...) needs a sentence in the Info.plist, or macOS gives the app silence
 *  instead of the thing, with no error anywhere. Because the apps are built and tried on Windows, that sentence is
 *  forgotten. Where it comes from, in the order it is looked for:
 *  <ol>
 *  <li>the libraries in the jar declare what they need, with a default sentence (PermissionScan.libraries): an app
 *      that uses a library gets its keys without remembering them;</li>
 *  <li>the pom's macosPermissions adds the app's own and may reword a library's; any Info.plist key is allowed, the
 *      names in MacPermission are only aliases;</li>
 *  <li>a usage key set the old way in macosInfoPlist counts too;</li>
 *  <li>the safety net: what the jar's classes SUGGEST (PermissionScan.strong) that nobody declared is reported. On a
 *      release build that stops the build, as Apple's submission check does; on any other build it warns. An app
 *      that is sure it does not need one says so in macosPermissionsNotNeeded, with the reason.</li>
 *  </ol>
 *  Nothing is required of an app where nothing is suspected. A plain-text report says what the app will ask for. */
final class MacPermissions {
    static final int MIN_TEXT = 20;
    /** id (a plist key, or a manual permission's name) to the text, and where the text came from. */
    final Map<String, String> asked = new LinkedHashMap<>();
    final Map<String, String> from = new LinkedHashMap<>();
    final Map<String, String> notNeeded = new LinkedHashMap<>();
    final List<String> problems = new ArrayList<>();
    final List<String> hints = new ArrayList<>();

    static MacPermissions of(Map<String, String> pom, Map<String, String> pomNotNeeded, Map<String, String> raw, PermissionScan scan) {
        var m = new MacPermissions();
        scan.libraries.forEach((k, text) -> m.ask(k, text, "library " + scan.libraryOf.get(k)));
        if (pom != null) pom.forEach((k, text) -> m.ask(k, text, "the pom"));
        if (raw != null) raw.forEach((k, text) -> {
            if (k.startsWith("NS") && k.endsWith("UsageDescription")) {
                m.asked.put(k, text);
                m.from.put(k, "macosInfoPlist");
            }
        });
        if (pomNotNeeded != null) pomNotNeeded.forEach((k, why) -> {
            if (why == null || why.strip().length() < 10) m.problems.add("<" + k + "> in macosPermissionsNotNeeded needs the reason, in a sentence.");
            m.notNeeded.put(MacPermission.id(k), why == null ? "" : why.strip());
        });
        scan.strong.forEach((p, where) -> {
            if (!m.covered(p)) {
                m.problems.add("the app's jar seems to use " + p.what + " (" + p.strong.get(0) + " in " + where + "), and nothing declares it. Without a text, macOS "
                        + (p.manual() ? "leaves the app with no access and no prompt." : "gives the app silence instead of " + p.what + ", with no error.")
                        + " Best: the library that does it declares it. Otherwise add <" + p.name() + ">the sentence the person reads</" + p.name() + "> to macosPermissions."
                        + " If you are sure it is not needed (say, the library code is never called), add <" + p.name() + ">why</" + p.name() + "> to macosPermissionsNotNeeded.");
            }
        });
        scan.weak.forEach((p, where) -> {
            if (!m.covered(p)) m.hints.add("the app may reach into " + p.what + " (" + p.weak.get(0) + " in " + where + "); if so, declare <" + p.name() + ">.");
        });
        return m;
    }

    private void ask(String name, String text, String source) {
        var id = MacPermission.id(name);
        var t = text == null ? "" : text.strip();
        if (MacPermission.of(name) == null && !(name.length() > 1 && Character.isUpperCase(name.charAt(0)))) {
            problems.add("<" + name + "> is neither a known name nor an Info.plist key (those start with a capital, like NSMicrophoneUsageDescription).");
        }
        if (t.length() < MIN_TEXT || t.toLowerCase().contains("todo")) {
            problems.add("<" + name + "> (" + source + ") needs a real sentence (at least " + MIN_TEXT + " characters): macOS shows it to the person, or it goes to whoever reviews the app.");
        }
        asked.put(id, t);
        from.put(id, source);
    }

    boolean covered(MacPermission p) {
        return asked.containsKey(p.id()) || notNeeded.containsKey(p.id());
    }

    /** auto (the default): a suspicion stops a release build and only warns otherwise; fail and warn force one way. */
    void enforce(Log log, String mode, boolean release) throws MojoExecutionException {
        if (mode != null && !List.of("auto", "fail", "warn").contains(mode)) throw new MojoExecutionException("macosPermissionsCheck must be auto, fail or warn, not " + mode);
        hints.forEach(h -> log.info("macOS permissions: " + h));
        if (problems.isEmpty()) return;
        var all = "macOS permissions (jarrunner PRP-61):\n  - " + String.join("\n  - ", problems);
        if ("warn".equals(mode) || !release && !"fail".equals(mode)) log.warn(all);
        else throw new MojoExecutionException(all + "\n(A release build stops on this; a build with macosPermissionsCheck=warn only reports it.)");
    }

    /** The Info.plist entries: every declared key that is a real plist key, not a manual permission's name. */
    Map<String, String> plist() {
        var m = new LinkedHashMap<String, String>();
        asked.forEach((id, text) -> {
            var p = MacPermission.of(id);
            if (p == null || !p.manual()) m.put(id, text);
        });
        return m;
    }

    private static String what(String id) {
        var p = MacPermission.of(id);
        return p == null ? id : p.what;
    }

    /** Plain text for whoever approves the app on a Mac: what it asks for, what the person reads, what is done by hand. */
    String report(String displayName, String bundleId) {
        var r = new ArrayList<String>();
        r.add("What " + displayName + " (" + bundleId + ") asks macOS for");
        var plist = plist();
        r.add("");
        if (plist.isEmpty() && asked.isEmpty()) r.add("Nothing that macOS protects was declared or suspected.");
        plist.forEach((k, text) -> r.add("- " + what(k) + " (" + k + ", from " + from.get(k) + "). macOS shows a prompt with an Allow button the first time the app uses it, and the person reads: \"" + text + "\""));
        asked.forEach((id, text) -> {
            if (!plist.containsKey(id)) r.add("- " + what(id) + " (from " + from.get(id) + "). No prompt: someone turns it on in System Settings, Privacy and Security, and macOS may ask for an administrator's password. Why: " + text);
        });
        if (!notNeeded.isEmpty()) r.add("");
        notNeeded.forEach((id, why) -> r.add("- Looked like " + what(id) + " but declared not needed: " + why));
        r.add("");
        r.add("To get the prompts, and keep the answers:");
        r.add("- Start the app from Finder, the Dock or `open`, never from a terminal: macOS gives the permission to the terminal, not to the app.");
        r.add("- The app is signed ad hoc, so an update that changes the binary may make macOS ask again.");
        r.add("- To be asked again from scratch: `tccutil reset All " + bundleId + "`.");
        return String.join("\n", r) + "\n";
    }

    File writeReport(File macosDir, String name, String displayName, String bundleId) throws Exception {
        macosDir.mkdirs();
        var f = new File(macosDir, name + ".permissions.txt");
        Files.writeString(f.toPath(), report(displayName, bundleId));
        return f;
    }
}
