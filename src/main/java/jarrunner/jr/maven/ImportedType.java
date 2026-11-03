package jarrunner.jr.maven;

import java.util.List;

/** PRP-61 (the old PRP-57): a file type that macOS has no name for (an extension no system type covers), so the app
 *  names it itself. Written as one entry of UTImportedTypeDeclarations; a DocumentType then lists its identifier among
 *  its utis. A pom element: {@code <importedType>} inside macosImportedTypes. conformsTo defaults to public.data. */
public final class ImportedType {
    String identifier;
    String description;
    List<String> conformsTo;
    List<String> extensions;
}
