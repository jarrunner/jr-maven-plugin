package jarrunner.jr.maven;

import java.util.List;

/** PRP-61 (the old PRP-57): one kind of file the app opens, for Finder's Open With and for making the app the default
 *  for it. Written as one entry of CFBundleDocumentTypes. A pom element: {@code <documentType>} inside macosDocumentTypes.
 *  role: Editor (default), Viewer, Shell or None. rank: how strongly the app claims the type, Alternate (default: offered
 *  in Open With), Default (preferred over an Alternate) or Owner. utis and extensions: at least one of them. */
public final class DocumentType {
    String name;
    String role;
    String rank;
    List<String> utis;
    List<String> extensions;
}
