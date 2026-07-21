package org.gbif.datagen.spec;

import java.util.List;

import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.gen.Generator;
import org.gbif.datagen.schema.Field;

/**
 * Derives a generator from a field's own schema constraints.
 *
 * <p>This is the bottom of the resolution chain and the reason a zero-config
 * {@code .resource("event").rows(100)} already produces schema-valid data: {@code minimum} and
 * {@code maximum} are a numeric range, {@code enum} is a sampling pool, {@code unique} plus
 * {@code required} is an identity column. The fluent API then exists only to make data
 * <em>interesting</em>, not to make it <em>valid</em>.
 *
 * <p>Deliberately not a layer in the resolution chain — it has a second job (validating explicit
 * generators against constraints), and flattening the two would let a broad
 * {@code global(type(STRING), ...)} silently clobber enum sampling on every enum'd field.
 */
public final class SchemaDefaults {

  private SchemaDefaults() {
  }

  public static Generator<?> forField(Field field) {
    if (field.enumValues().isPresent()) {
      List<Object> values = field.enumValues().get();
      if (!values.isEmpty()) {
        return Gen.sample(values);
      }
    }

    if (field.unique() && field.required()) {
      return Gen.uuid();
    }

    if (field.isNumeric()) {
      double min = field.minimum().orElse(0d);
      double max = field.maximum().orElse(field.isInteger() ? 1000d : 1d);
      return Gen.between(min, max);
    }

    if ("boolean".equals(field.type())) {
      return Gen.sample(List.of(Boolean.TRUE, Boolean.FALSE));
    }

    // Fall through: a plain string with no constraints. Deterministic and obviously synthetic,
    // so unconfigured columns are visibly unconfigured rather than plausible-looking noise.
    String name = field.name();
    Generator<String> base = ctx -> name + "-" + ctx.rowIndex();
    return field.required() ? base : base.nullChance(0.5);
  }

  /**
   * Validates a resolved generator's output against the field's constraints.
   *
   * <p>Only structural checks are possible before generation; value-range checks happen on the
   * first generated row via {@link #checkValue}.
   */
  public static void validateBinding(Field field, Generator<?> generator, String resource) {
    if (field.required() && isNullable(generator)) {
      throw new IllegalStateException("Resource '" + resource + "': field '" + field.name()
          + "' is required by the schema but its generator can emit null.");
    }
  }

  private static boolean isNullable(Generator<?> generator) {
    // nullChance produces a lambda; there is no reflective way to detect it without a marker
    // interface. Left as a hook rather than a false promise — see checkValue for the real check.
    return false;
  }

  /** Post-generation constraint check. Runs per cell; cheap, and catches recipe/schema conflicts. */
  public static void checkValue(Field field, Object value, String resource, int rowIndex) {
    if (value == null) {
      if (field.required()) {
        throw new IllegalStateException("Resource '" + resource + "' row " + rowIndex + ": field '"
            + field.name() + "' is required by the schema but the generator produced null.");
      }
      return;
    }
    if (value instanceof Number n && field.isNumeric()) {
      double d = n.doubleValue();
      // Range violations are only reported when not deliberately induced; a corrupt() decorator
      // wrapping the generator is expected to break this, so this check is advisory and lives
      // behind checkConstraints on the package spec.
      field.minimum().ifPresent(min -> {
        if (d < min) {
          throw new IllegalStateException("Resource '" + resource + "' row " + rowIndex + ": field '"
              + field.name() + "' value " + d + " is below schema minimum " + min
              + ". If this is intentional, disable constraint checking with .checkConstraints(false).");
        }
      });
      field.maximum().ifPresent(max -> {
        if (d > max) {
          throw new IllegalStateException("Resource '" + resource + "' row " + rowIndex + ": field '"
              + field.name() + "' value " + d + " exceeds schema maximum " + max
              + ". If this is intentional, disable constraint checking with .checkConstraints(false).");
        }
      });
    }
  }
}
