package jarrunner.jr.maven;

import java.util.List;

/** PRP-61: the well-known things an app asks macOS for. NOT a closed list: any Info.plist key may be declared, by its
 *  own name; these are only short aliases for the common ones (microphone for NSMicrophoneUsageDescription) and the
 *  evidence the build looks for in a jar to suspect that an app uses one.
 *  A permission with a plist key is asked for with a prompt the first time the app touches the thing, showing the
 *  app's text from that key. One with no key (null) is turned on by hand in System Settings, Privacy and Security:
 *  macOS gives such an app no prompt and no place to say why, so its text goes only into the report.
 *  evidence: names that, found in a class file, mean the app uses the thing. A name starting with "?" is a weak hint
 *  (a path string): it is only mentioned, never a reason to warn. */
enum MacPermission {
    microphone("NSMicrophoneUsageDescription", "the microphone", "javax/sound/sampled/TargetDataLine"),
    camera("NSCameraUsageDescription", "the camera", "com/github/sarxos/webcam/"),
    documents("NSDocumentsFolderUsageDescription", "the Documents folder", "?/Documents"),
    desktop("NSDesktopFolderUsageDescription", "the Desktop folder", "?/Desktop"),
    downloads("NSDownloadsFolderUsageDescription", "the Downloads folder", "?/Downloads"),
    removableVolumes("NSRemovableVolumesUsageDescription", "USB drives and other removable disks", "?/Volumes"),
    networkVolumes("NSNetworkVolumesUsageDescription", "network drives"),
    speechRecognition("NSSpeechRecognitionUsageDescription", "Apple's speech recognition"),
    appleEvents("NSAppleEventsUsageDescription", "other apps, to control them with scripts", "osascript"),
    localNetwork("NSLocalNetworkUsageDescription", "devices on the local network"),
    bluetooth("NSBluetoothAlwaysUsageDescription", "Bluetooth devices"),
    contacts("NSContactsUsageDescription", "the Contacts"),
    screenRecording(null, "the screen contents (Screen Recording)", "createScreenCapture"),
    accessibility(null, "control of other apps (Accessibility)", "com/github/kwhat/jnativehook/"),
    inputMonitoring(null, "keystrokes typed in other apps (Input Monitoring)", "com/github/kwhat/jnativehook/"),
    fullDiskAccess(null, "every file on the disk (Full Disk Access)");

    final String plistKey;
    final String what;
    final List<String> strong;
    final List<String> weak;

    MacPermission(String plistKey, String what, String... evidence) {
        this.plistKey = plistKey;
        this.what = what;
        this.strong = List.of(evidence).stream().filter(e -> !e.startsWith("?")).toList();
        this.weak = List.of(evidence).stream().filter(e -> e.startsWith("?")).map(e -> e.substring(1)).toList();
    }

    boolean manual() {
        return plistKey == null;
    }

    /** What the declaration is stored under: the plist key, or for a manual one its own name. */
    String id() {
        return manual() ? name() : plistKey;
    }

    /** The permission an alias or a plist key names, else null (then the name is a raw plist key of its own). */
    static MacPermission of(String nameOrKey) {
        for (var p : values()) if (nameOrKey.equals(p.name()) || nameOrKey.equals(p.plistKey)) return p;
        return null;
    }

    /** The id a declaration is stored under: an alias becomes its plist key; anything else is itself. */
    static String id(String nameOrKey) {
        var p = of(nameOrKey);
        return p == null ? nameOrKey : p.id();
    }
}
