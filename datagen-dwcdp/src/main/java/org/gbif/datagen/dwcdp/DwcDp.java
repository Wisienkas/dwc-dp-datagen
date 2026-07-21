package org.gbif.datagen.dwcdp;

import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Key;

/** Entry point for DwC-DP schema bundles, plus the term URIs recipes key globals on. */
public final class DwcDp {

  private DwcDp() {
  }

  /**
   * Loads a bundle by its exact version string, as it appears in {@code schemas/bundles.json}.
   *
   * <p>A {@code String} rather than a typed constant deliberately: bundle names are not semantic
   * ({@code 0.1}, {@code 1.0_DEV}), so a constant would need recompiling every time the schemas
   * jar adds one. Same reasoning as loading schemas at runtime instead of generating code —
   * schema version is data, not a build step.
   */
  public static DwcDpSchemaBundle bundle(String version) {
    return DwcDpSchemaBundle.load(version);
  }

  private static final String DWC = "http://rs.tdwg.org/dwc/terms/";
  private static final String DCTERMS = "http://purl.org/dc/terms/";

  /** Shorthand for {@code Key.term(...)}. */
  public static Key term(String termUri) {
    return Key.term(termUri);
  }

  /** Shorthand for {@code Key.type(...)}. */
  public static Key type(String type) {
    return Key.type(type);
  }

  public static Key termOfField(SchemaBundle bundle, String table, String field) {
    return Key.termOfField(bundle, table, field);
  }
}
