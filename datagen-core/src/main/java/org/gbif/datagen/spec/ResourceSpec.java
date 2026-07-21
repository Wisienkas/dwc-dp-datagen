package org.gbif.datagen.spec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Generator;
import org.gbif.datagen.schema.Field;
import org.gbif.datagen.schema.TableSchema;

/** A resolved, immutable resource: fields chosen, generators bound, sizing decided. */
public record ResourceSpec(String name, TableSchema schema, List<Field> fields,
                           Map<String, Generator<?>> generators, List<RefSpec> refs,
                           Optional<Integer> rowCount, Optional<Anchor> anchor) {

  /** Sizing driven by a parent relation rather than an absolute row count. */
  public record Anchor(String parentResource, Dist dist) {
  }

  public List<String> fieldNames() {
    return fields.stream().map(Field::name).toList();
  }

  public Generator<?> generator(String field) {
    Generator<?> g = generators.get(field);
    if (g == null) {
      throw new IllegalStateException("No generator bound for " + name + "." + field);
    }
    return g;
  }
}
