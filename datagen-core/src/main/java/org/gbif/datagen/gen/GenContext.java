package org.gbif.datagen.gen;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.gbif.datagen.schema.Field;

/**
 * Everything a generator can see when producing one cell.
 *
 * <p>Exposing the partially-built row and the FK pools here is what lets {@code lookup},
 * {@code derive}, {@code inherit} and {@code constant} all share a single one-method interface:
 * correlated fields ({@code countryCode} agreeing with {@code country}) need no special
 * mechanism, they just read {@link #get}.
 */
public interface GenContext {

  /** Deterministically derived per cell: hash(masterSeed, resource, field, rowIndex). */
  long seed();

  /** A {@link Random} seeded from {@link #seed()}. Freshly created per cell. */
  Random random();

  String resource();

  String field();

  int rowIndex();

  /** The field descriptor being generated, when one exists in the schema. */
  Optional<Field> fieldSchema();

  /** A value already generated on the current row. Null if not yet generated or absent. */
  Object get(String field);

  Row row();

  /**
   * Values of {@code field} from an already-generated resource. Ordering is generation order,
   * and the engine guarantees the target resource is complete before this is callable.
   */
  List<Object> pool(String resource, String field);

  /** The anchoring parent row, when this resource was sized with {@code .per(parent, dist)}. */
  Optional<Row> parentRow(String resource);

  /** Any parent row, when exactly one parent anchor exists. */
  Optional<Row> parentRow();

  /**
   * Whether {@code field}'s generator has already run on this row — see {@link Row#isGenerated}.
   * A generator that depends on another field's value (chiefly {@code template()}) must check
   * this before reading {@link #get}, so it can request a retry via
   * {@link FieldNotReadyException} instead of treating "not yet generated" as "generated, null."
   */
  boolean isGenerated(String field);
}
