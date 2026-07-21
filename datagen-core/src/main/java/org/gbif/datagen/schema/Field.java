package org.gbif.datagen.schema;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A single field descriptor from a Frictionless table schema.
 *
 * <p>{@code raw} is the complete, unmodified JSON object as loaded. Accessors below are
 * conveniences over it — they never become the source of truth. Anything not exposed here is
 * still reachable via {@link #raw()} directly.
 */
public record Field(String name, String type, Map<String, Object> raw) {

  public static Field fromJson(Map<String, Object> json) {
    Object name = json.get("name");
    if (name == null) {
      throw new IllegalArgumentException("Field descriptor has no 'name': " + json);
    }
    Object type = json.getOrDefault("type", "string");
    return new Field(String.valueOf(name), String.valueOf(type), json);
  }

  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> constraints() {
    Object c = raw.get("constraints");
    return c instanceof Map ? Optional.of((Map<String, Object>) c) : Optional.empty();
  }

  public boolean required() {
    return constraints().map(c -> Boolean.TRUE.equals(c.get("required"))).orElse(false);
  }

  public boolean unique() {
    return constraints().map(c -> Boolean.TRUE.equals(c.get("unique"))).orElse(false);
  }

  public Optional<Double> minimum() {
    return constraints().map(c -> c.get("minimum")).filter(Number.class::isInstance)
      .map(n -> ((Number) n).doubleValue());
  }

  public Optional<Double> maximum() {
    return constraints().map(c -> c.get("maximum")).filter(Number.class::isInstance)
      .map(n -> ((Number) n).doubleValue());
  }

  public Optional<Integer> minLength() {
    return constraints().map(c -> c.get("minLength")).filter(Number.class::isInstance)
      .map(n -> ((Number) n).intValue());
  }

  public Optional<Integer> maxLength() {
    return constraints().map(c -> c.get("maxLength")).filter(Number.class::isInstance)
      .map(n -> ((Number) n).intValue());
  }

  public Optional<String> pattern() {
    return constraints().map(c -> c.get("pattern")).map(String::valueOf);
  }

  @SuppressWarnings("unchecked")
  public Optional<List<Object>> enumValues() {
    return constraints().map(c -> c.get("enum")).filter(List.class::isInstance).map(e -> (List<Object>) e);
  }

  public Optional<String> format() {
    return Optional.ofNullable(raw.get("format")).map(String::valueOf);
  }

  public Optional<String> title() {
    return Optional.ofNullable(raw.get("title")).map(String::valueOf);
  }

  public Optional<String> description() {
    return Optional.ofNullable(raw.get("description")).map(String::valueOf);
  }

  /** The unversioned source term URI, stable across the 0.1 -> 1.0 field renames. */
  public Optional<String> isVersionOf() {
    return Optional.ofNullable(raw.get("dcterms:isVersionOf")).map(String::valueOf);
  }

  /** The DwC-DP guide's field-level {@code dcterms:references}, when present. */
  public Optional<String> references() {
    return Optional.ofNullable(raw.get("dcterms:references")).map(String::valueOf);
  }

  /** Field-level {@code missingValues} override, as raw strings, when the field declares one. */
  @SuppressWarnings("unchecked")
  public Optional<List<Object>> missingValues() {
    Object mv = raw.get("missingValues");
    return mv instanceof List ? Optional.of((List<Object>) mv) : Optional.empty();
  }

  public boolean isNumeric() {
    return "number".equals(type) || "integer".equals(type);
  }

  public boolean isInteger() {
    return "integer".equals(type);
  }
}
