package org.gbif.datagen.spec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.gbif.datagen.schema.Field;
import org.gbif.datagen.schema.TableSchema;

/**
 * Decides which of a schema's fields appear in a resource.
 *
 * <p>Selection decides <em>which</em> fields; the schema decides <em>what order</em>. The guide
 * requires the descriptor's {@code fields} order to equal the CSV column order, so emission is
 * always in schema order regardless of the order fields were named in a recipe. That keeps two
 * recipes selecting the same fields byte-identical.
 *
 * <p>Beyond the chosen mode, some fields are forced in and cannot be removed:
 *
 * <ul>
 *   <li>{@code constraints.required == true}
 *   <li>any field given an explicit generator (so {@code .field(name, gen)} doubles as selection)
 *   <li>any ref's source field
 *   <li>any field targeted by a ref from this or another resource — this is the subtle one:
 *       {@code occurrenceID} is a {@code weakPrimaryKey}, so it is <em>not</em> required, and a
 *       naive MIN selection would drop the very column a weak ref points at.
 * </ul>
 */
public final class FieldSelection {

  /** Starting point before additions and removals. */
  public enum Mode {
    /** Only fields with {@code constraints.required == true}. */
    REQUIRED,
    /** Every field the schema defines. */
    ALL,
    /** Only fields named explicitly (plus the forced set). */
    EXPLICIT
  }

  private Mode mode = Mode.REQUIRED;
  private final Set<String> explicit = new LinkedHashSet<>();
  private final Set<String> added = new LinkedHashSet<>();
  private final Set<String> removed = new LinkedHashSet<>();

  public void mode(Mode mode) {
    this.mode = mode;
  }

  public Mode mode() {
    return mode;
  }

  public void explicit(List<String> fields) {
    mode = Mode.EXPLICIT;
    explicit.addAll(fields);
  }

  public void add(List<String> fields) {
    added.addAll(fields);
  }

  public void remove(List<String> fields) {
    removed.addAll(fields);
  }

  /**
   * Resolves the effective field list, in schema order.
   *
   * @param forced fields that must appear regardless of mode or removals
   */
  public List<Field> resolve(TableSchema schema, Set<String> forced, String resourceName) {
    validateNames(schema, resourceName);

    Set<String> selected = new LinkedHashSet<>();
    switch (mode) {
      case ALL -> schema.fields().forEach(f -> selected.add(f.name()));
      case REQUIRED -> schema.requiredFields().forEach(f -> selected.add(f.name()));
      case EXPLICIT -> {
        selected.addAll(explicit);
        schema.requiredFields().forEach(f -> selected.add(f.name()));
      }
    }
    selected.addAll(added);
    selected.addAll(forced);

    Set<String> conflicting = new TreeSet<>(removed);
    conflicting.retainAll(forced);
    if (!conflicting.isEmpty()) {
      throw new IllegalStateException("Resource '" + resourceName + "': cannot remove "
          + conflicting + " — " + explainForced(schema, conflicting)
          + ". Removing them would make the package structurally invalid.");
    }
    selected.removeAll(removed);

    List<Field> result = new ArrayList<>();
    for (Field f : schema.fields()) {
      if (selected.contains(f.name())) {
        result.add(f);
      }
    }
    if (result.isEmpty()) {
      throw new IllegalStateException("Resource '" + resourceName + "' selected no fields");
    }
    return List.copyOf(result);
  }

  private String explainForced(TableSchema schema, Set<String> names) {
    List<String> reasons = new ArrayList<>();
    for (String n : names) {
      boolean required = schema.field(n).map(Field::required).orElse(false);
      reasons.add(n + (required ? " is required by the schema" : " is the source or target of a reference"));
    }
    return String.join("; ", reasons);
  }

  private void validateNames(TableSchema schema, String resourceName) {
    Set<String> unknown = new TreeSet<>();
    for (String n : explicit) {
      if (!schema.hasField(n)) {
        unknown.add(n);
      }
    }
    for (String n : added) {
      if (!schema.hasField(n)) {
        unknown.add(n);
      }
    }
    for (String n : removed) {
      if (!schema.hasField(n)) {
        unknown.add(n);
      }
    }
    if (!unknown.isEmpty()) {
      for (String n : unknown) {
        throw new IllegalStateException("Resource '" + resourceName + "': no field '" + n
            + "' in table '" + schema.name() + "'." + suggest(n, schema.fieldNames()));
      }
    }
  }

  /**
   * Cheap typo hint against an arbitrary candidate set. When a close match exists (edit distance
   * <= 3), names it directly. When it doesn't — which happens for real when the intended field
   * isn't merely misspelled but renamed or absent entirely, as opposed to a typo Levenshtein can
   * catch — falls back to listing every candidate, since a silent "no suggestion" leaves the
   * caller no better off than the bare error alone.
   */
  static String suggest(String name, java.util.Collection<String> candidates) {
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : candidates) {
      int d = levenshtein(name.toLowerCase(), candidate.toLowerCase());
      if (d < bestDistance) {
        bestDistance = d;
        best = candidate;
      }
    }
    if (best != null && bestDistance <= 3) {
      return " Did you mean '" + best + "'?";
    }
    List<String> sorted = candidates.stream().sorted().toList();
    return sorted.isEmpty() ? " (no fields available)" : " Available fields: " + sorted;
  }

  private static int levenshtein(String a, String b) {
    int[] prev = new int[b.length() + 1];
    int[] curr = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      curr[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[b.length()];
  }
}
