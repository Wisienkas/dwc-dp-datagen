package org.gbif.datagen.spec;

import java.util.Objects;

import org.gbif.datagen.schema.Field;
import org.gbif.datagen.schema.SchemaBundle;

/**
 * Key for a global generator binding.
 *
 * <p>Term-keyed is the version-portable layer: {@code dcterms:isVersionOf} is present on every
 * field in both 0.1 and 1.0 and survives the field renames ({@code eventID} -> {@code event_pk}),
 * so a term-keyed global written once applies to both schema versions unchanged. Field-keyed
 * config is inherently version-bound; term-keyed is not.
 */
public sealed interface Key {

  boolean matches(Field field);

  String describe();

  static Key term(String termUri) {
    return new TermKey(termUri);
  }

  static Key type(String type) {
    return new TypeKey(type);
  }

  /**
   * Resolves a term-keyed {@link Key} from a live field, instead of a hand-maintained URI
   * constant. There is no generated or hand-written list of DwC-DP terms anywhere — with ~200+
   * terms across the guide, that list would either need codegen (which reintroduces exactly the
   * "regenerate and re-release on every schema bump" coupling that loading schemas at runtime was
   * meant to avoid) or hand-maintenance (which won't stay correct). This reads the URI straight
   * off the bundle you already loaded.
   *
   * <p>The returned {@code Key} still matches every table carrying that term — {@code
   * termOfField(bundle, "event", "decimalLatitude")} also matches {@code occurrence
   * .decimalLatitude} and {@code material.decimalLatitude} — because {@link TermKey#matches}
   * compares only the URI, not which table it was resolved from. Cross-table portability, the
   * entire point of term-keying, is unaffected by resolving the URI this way.
   *
   * @throws IllegalArgumentException if the field has no {@code dcterms:isVersionOf}
   */
  static Key termOfField(SchemaBundle bundle, String table, String field) {
    String uri = bundle.requireSchema(table).field(field)
      .flatMap(Field::isVersionOf)
      .orElseThrow(() -> new IllegalArgumentException(
        "Field '" + table + "." + field + "' has no dcterms:isVersionOf to key a term on"));
    return term(uri);
  }

  /** Matches any field whose {@code dcterms:isVersionOf} equals the given URI. */
  record TermKey(String termUri) implements Key {
    public TermKey {
      Objects.requireNonNull(termUri, "termUri");
    }

    @Override
    public boolean matches(Field field) {
      return field.isVersionOf().map(termUri::equals).orElse(false);
    }

    @Override
    public String describe() {
      return "term(" + termUri + ")";
    }
  }

  /** Matches any field of the given Frictionless type. */
  record TypeKey(String type) implements Key {
    public TypeKey {
      Objects.requireNonNull(type, "type");
    }

    @Override
    public boolean matches(Field field) {
      return type.equals(field.type());
    }

    @Override
    public String describe() {
      return "type(" + type + ")";
    }
  }
}
