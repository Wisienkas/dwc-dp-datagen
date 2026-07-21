package org.gbif.datagen.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Frictionless table schema: ordered fields, primary key, foreign keys, plus the raw object.
 *
 * <p>{@code weakPrimaryKey}/{@code weakForeignKeys} are parsed the same way as their strong
 * counterparts. These are not part of the ratified DwC-DP guide (which documents only {@code
 * primaryKey}/{@code foreignKeys}) — they're a 1.0_DEV-prerelease extension. Treating them as an
 * unparsed detail of {@code raw} would have meant every weak relationship (the
 * {@code material.derivedFromOccurrenceID}-style natural-key joins) got no ordering, no
 * forced-field treatment, and no default lookup generator — silently, since nothing would fail,
 * the fields would just come out as unresolved placeholder strings.
 */
public record TableSchema(String name, List<Field> fields, List<String> primaryKey,
                          List<String> weakPrimaryKey, List<ForeignKey> foreignKeys,
                          List<ForeignKey> weakForeignKeys, Map<String, Object> raw) {

  /**
   * Builds a schema from a loaded JSON document.
   *
   * <p>Tolerant of two shapes: the schema object at the top level ({@code {"fields": [...]}}),
   * or nested under a {@code "schema"} key. The standalone table-schema files and the schema
   * fragment embedded in a descriptor differ on this, and it costs nothing to accept both.
   */
  @SuppressWarnings("unchecked")
  public static TableSchema fromJson(String name, Map<String, Object> json) {
    Map<String, Object> schema = json;
    if (!json.containsKey("fields") && json.get("schema") instanceof Map) {
      schema = (Map<String, Object>) json.get("schema");
    }

    Object fieldsObj = schema.get("fields");
    if (!(fieldsObj instanceof List)) {
      throw new IllegalArgumentException("Table schema '" + name + "' has no 'fields' array");
    }

    List<Field> fields = new ArrayList<>();
    for (Object f : (List<Object>) fieldsObj) {
      if (!(f instanceof Map)) {
        throw new IllegalArgumentException("Table schema '" + name + "' has a non-object field entry");
      }
      fields.add(Field.fromJson((Map<String, Object>) f));
    }

    List<String> primaryKey = keyList(schema.get("primaryKey"));
    List<String> weakPrimaryKey = keyList(schema.get("weakPrimaryKey"));
    List<ForeignKey> foreignKeys = fkList(schema.get("foreignKeys"), name, true);
    List<ForeignKey> weakForeignKeys = fkList(schema.get("weakForeignKeys"), name, false);

    return new TableSchema(name, List.copyOf(fields), primaryKey, weakPrimaryKey,
                           foreignKeys, weakForeignKeys, new LinkedHashMap<>(schema));
  }

  private static List<String> keyList(Object value) {
    List<String> keys = new ArrayList<>();
    if (value instanceof String s) {
      keys.add(s);
    } else if (value instanceof List<?> list) {
      list.forEach(k -> keys.add(String.valueOf(k)));
    }
    return List.copyOf(keys);
  }

  @SuppressWarnings("unchecked")
  private static List<ForeignKey> fkList(Object value, String tableName, boolean requireReference) {
    List<ForeignKey> result = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object fk : list) {
        if (fk instanceof Map) {
          result.add(ForeignKey.fromJson((Map<String, Object>) fk, tableName, requireReference));
        }
      }
    }
    return List.copyOf(result);
  }

  public Optional<Field> field(String fieldName) {
    return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
  }

  public boolean hasField(String fieldName) {
    return field(fieldName).isPresent();
  }

  public List<String> fieldNames() {
    return fields.stream().map(Field::name).toList();
  }

  public List<Field> requiredFields() {
    return fields.stream().filter(Field::required).toList();
  }

  /** All declared relationships, strong and weak together — the merged view most callers want. */
  public List<ForeignKey> allForeignKeys() {
    List<ForeignKey> all = new ArrayList<>(foreignKeys);
    all.addAll(weakForeignKeys);
    return List.copyOf(all);
  }
}
