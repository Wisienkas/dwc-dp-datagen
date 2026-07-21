package org.gbif.datagen.schema;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A foreign key declared in a table schema.
 *
 * <p>Frictionless's {@code foreignKeys} always requires a {@code reference} — that's spec, not
 * a choice made here. {@code weakForeignKeys} is a DwC-DP-only extension with no such
 * requirement: a weak entry can mark a field as a weak/natural key without necessarily pointing
 * at anything ({@code refResource}/{@code refField} are {@code null} in that case). Callers that
 * need a target for ordering, forcing, or default-lookup purposes must check
 * {@link #hasTarget()} first — a targetless weak FK is real and valid, not a parse failure.
 */
public record ForeignKey(String field, String predicate, String refResource, String refField,
                         Map<String, Object> raw) {

  @SuppressWarnings("unchecked")
  public static ForeignKey fromJson(Map<String, Object> json, String owningTable) {
    return fromJson(json, owningTable, true);
  }

  /**
   * @param requireReference {@code true} for {@code foreignKeys} (Frictionless requires
   *     {@code reference}); {@code false} for {@code weakForeignKeys}, where a missing
   *     {@code reference} means "no target" rather than a malformed entry.
   */
  @SuppressWarnings("unchecked")
  public static ForeignKey fromJson(Map<String, Object> json, String owningTable, boolean requireReference) {
    String field = single(json.get("fields"), owningTable, "fields");
    Object predicate = json.get("predicate");
    Object refObj = json.get("reference");

    if (refObj == null) {
      if (requireReference) {
        throw new IllegalArgumentException(
          "Foreign key on table '" + owningTable + "' has no 'reference' object: " + json);
      }
      return new ForeignKey(field, predicate == null ? null : String.valueOf(predicate), null, null, json);
    }

    if (!(refObj instanceof Map)) {
      throw new IllegalArgumentException(
        "Foreign key on table '" + owningTable + "' has a non-object 'reference': " + json);
    }
    Map<String, Object> ref = (Map<String, Object>) refObj;
    Object resource = ref.get("resource");
    String refField = single(ref.get("fields"), owningTable, "reference.fields");

    // Frictionless uses an empty resource string to mean "this table".
    String refResource = (resource == null || String.valueOf(resource).isEmpty())
      ? owningTable
      : String.valueOf(resource);

    return new ForeignKey(field, predicate == null ? null : String.valueOf(predicate),
                          refResource, refField, json);
  }

  private static String single(Object value, String table, String property) {
    if (value instanceof String s) {
      return s;
    }
    if (value instanceof List<?> list) {
      if (list.size() != 1) {
        throw new IllegalArgumentException("Composite keys are not supported (table '" + table
                                           + "', property '" + property + "' had " + list.size() + " entries)");
      }
      return String.valueOf(list.get(0));
    }
    throw new IllegalArgumentException(
      "Table '" + table + "' foreign key property '" + property + "' must be a string or array");
  }

  /** Whether this FK actually names a target. False for a referenceless weak FK. */
  public boolean hasTarget() {
    return refResource != null && refField != null;
  }

  public boolean isSelfReference() {
    return hasTarget() && field != null; // refResource equality with owning table checked by callers
  }
}
