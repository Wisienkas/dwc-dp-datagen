package org.gbif.datagen.schema;

import java.util.Optional;
import java.util.Set;

/**
 * The boundary between {@code datagen-core} and any concrete standard.
 *
 * <p>Core speaks Frictionless Table Schema and nothing else — it never learns what a DwC-DP
 * profile is, which is what keeps it reusable for camtrap-dp and friends. {@code datagen-dwcdp}
 * implements this by reading {@code bundles.json} -> {@code index.json} -> {@code table-schemas/}
 * off the classpath.
 */
public interface SchemaBundle {

  /** Names of every table this bundle defines. */
  Set<String> tableNames();

  Optional<TableSchema> schema(String tableName);

  /**
   * The value to emit as the descriptor's {@code profile} property, if this standard has one.
   * Returned verbatim from the source document; never synthesized.
   */
  Optional<String> profileUrl();

  /** Identifies the bundle in errors and run metadata (e.g. {@code "dwc-dp:1.0_DEV"}). */
  String coordinate();

  default TableSchema requireSchema(String tableName) {
    return schema(tableName).orElseThrow(() -> new IllegalArgumentException(
        "No table '" + tableName + "' in bundle " + coordinate() + ". Available: "
            + tableNames().stream().sorted().toList()));
  }
}
