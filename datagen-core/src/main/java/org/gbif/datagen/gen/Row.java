package org.gbif.datagen.gen;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A single generated record, keyed by field name. */
public final class Row {

  private final Map<String, Object> values = new LinkedHashMap<>();
  private final Set<String> generated = new LinkedHashSet<>();

  public Object get(String field) {
    return values.get(field);
  }

  public String getString(String field) {
    Object v = values.get(field);
    return v == null ? null : String.valueOf(v);
  }

  /**
   * Whether {@code field}'s generator has already run on this row — true even if it produced
   * null. This is distinct from {@link #get} returning null, which is ambiguous between "not
   * yet generated" and "generated, value is null." Anything that needs to retry once a
   * dependency becomes available (chiefly {@code template()}) must check this, not null-check
   * {@link #get}.
   */
  public boolean isGenerated(String field) {
    return generated.contains(field);
  }

  public boolean has(String field) {
    return values.get(field) != null;
  }

  public void put(String field, Object value) {
    values.put(field, value);
    generated.add(field);
  }

  public Map<String, Object> values() {
    return values;
  }

  @Override
  public String toString() {
    return values.toString();
  }
}
