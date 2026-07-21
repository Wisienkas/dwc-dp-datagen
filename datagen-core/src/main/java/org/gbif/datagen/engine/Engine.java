package org.gbif.datagen.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.gbif.datagen.gen.FieldNotReadyException;
import org.gbif.datagen.gen.GenContext;
import org.gbif.datagen.gen.Generator;
import org.gbif.datagen.gen.Row;
import org.gbif.datagen.schema.Field;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.ResourceSpec;
import org.gbif.datagen.spec.SchemaDefaults;

/** Runs a {@link DataPackageSpec} to produce rows. */
public final class Engine {

  private final DataPackageSpec spec;

  public Engine(DataPackageSpec spec) {
    this.spec = spec;
  }

  public GeneratedPackage run(long masterSeed) {
    Map<String, List<Row>> generated = new LinkedHashMap<>();

    for (String resourceName : spec.generationOrder()) {
      ResourceSpec resource = spec.resource(resourceName);
      generated.put(resourceName, generateResource(resource, generated, masterSeed));
    }

    return new GeneratedPackage(spec, generated, masterSeed);
  }

  private List<Row> generateResource(ResourceSpec resource, Map<String, List<Row>> generated,
                                     long masterSeed) {
    List<Row> rows = new ArrayList<>();

    if (resource.anchor().isPresent()) {
      ResourceSpec.Anchor anchor = resource.anchor().get();
      List<Row> parents = generated.get(anchor.parentResource());
      if (parents == null) {
        throw new IllegalStateException("Resource '" + resource.name() + "' anchors on '"
            + anchor.parentResource() + "', which has not been generated. This is an ordering bug.");
      }
      for (int p = 0; p < parents.size(); p++) {
        Row parent = parents.get(p);
        // Fan-out count is drawn from a stream keyed on the parent row, so changing the child's
        // fields never reshuffles how many children each parent gets.
        Random countRandom = new Random(
            Seeds.cell(masterSeed, resource.name(), "__cardinality__", p));
        int count = anchor.dist().sample(countRandom);
        for (int i = 0; i < count; i++) {
          rows.add(generateRow(resource, generated, rows, masterSeed, rows.size(),
              Map.of(anchor.parentResource(), parent)));
        }
      }
    } else {
      int count = resource.rowCount().orElseThrow();
      for (int i = 0; i < count; i++) {
        rows.add(generateRow(resource, generated, rows, masterSeed, i, Map.of()));
      }
    }
    return rows;
  }

  private Row generateRow(ResourceSpec resource, Map<String, List<Row>> generated,
                          List<Row> ownRowsSoFar, long masterSeed, int rowIndex,
                          Map<String, Row> parents) {
    Row row = new Row();
    List<Field> pending = new ArrayList<>(resource.fields());

    while (!pending.isEmpty()) {
      List<Field> stillPending = new ArrayList<>();
      List<String> waitingOn = new ArrayList<>();
      boolean progressed = false;

      for (Field field : pending) {
        Generator<?> generator = resource.generator(field.name());
        GenContext ctx = new EngineContext(resource, field, row, rowIndex, masterSeed, generated,
                                           ownRowsSoFar, parents);
        try {
          Object value = generator.generate(ctx);
          if (spec.checkConstraints()) {
            SchemaDefaults.checkValue(field, value, resource.name(), rowIndex);
          }
          row.put(field.name(), value);
          progressed = true;
        } catch (FieldNotReadyException e) {
          stillPending.add(field);
          waitingOn.add(field.name() + " (waiting on '" + e.awaitedField() + "')");
        }
      }

      if (!progressed) {
        // Guarantees termination: every iteration of the outer while either shrinks `pending`
        // (progressed == true) or stops here. No pathological recipe can loop forever — the
        // worst case is one full pass per still-unresolved field, which for realistic templates
        // (referencing one or two other fields) is 1-2 passes, not a real cost.
        throw new IllegalStateException("Resource '" + resource.name() + "' row " + rowIndex
                                        + ": " + stillPending.size() + " field(s) could not be resolved after a full pass made"
                                        + " no further progress — this is a cyclic or otherwise unresolvable dependency, not"
                                        + " just an ordering issue: " + waitingOn
                                        + ". Likely causes: two fields whose generators reference each other, a"
                                        + " template()/deriveAfter() naming a field that was never selected onto this"
                                        + " resource, or a typo in a referenced field name.");
      }
      pending = stillPending;
    }
    return row;
  }

  /**
   * Per-cell context. Pools are read lazily so an unused lookup costs nothing.
   *
   * <p>Record components are named {@code resourceSpec}/{@code fieldSpec} rather than
   * {@code resource}/{@code field} — {@link GenContext} declares {@code resource()} and
   * {@code field()} returning {@code String}, and a record's auto-generated accessor must match
   * its component's declared type exactly, so a component literally named {@code resource} of
   * type {@link ResourceSpec} cannot satisfy a {@code String resource()} interface method.
   */
  private record EngineContext(ResourceSpec resourceSpec, Field fieldSpec, Row row, int rowIndex,
                               long masterSeed, Map<String, List<Row>> generated,
                               List<Row> ownRowsSoFar, Map<String, Row> parents)
      implements GenContext {

    @Override
    public long seed() {
      return Seeds.cell(masterSeed, resourceSpec.name(), fieldSpec.name(), rowIndex);
    }

    @Override
    public Random random() {
      return new Random(seed());
    }

    @Override
    public String resource() {
      return resourceSpec.name();
    }

    @Override
    public String field() {
      return fieldSpec.name();
    }

    @Override
    public Optional<Field> fieldSchema() {
      return Optional.of(fieldSpec);
    }

    @Override
    public Object get(String fieldName) {
      return row.get(fieldName);
    }

    @Override
    public List<Object> pool(String resourceName, String fieldName) {
      // A resource can read its own already-generated rows — this is what makes self-references
      // structurally acyclic rather than merely conventionally so.
      List<Row> source = resourceName.equals(resourceSpec.name())
          ? ownRowsSoFar
          : generated.get(resourceName);
      if (source == null) {
        throw new IllegalStateException("Resource '" + resourceSpec.name() + "' requested a pool from '"
            + resourceName + "', which has not been generated yet. This is an ordering bug.");
      }
      List<Object> values = new ArrayList<>(source.size());
      for (Row r : source) {
        Object v = r.get(fieldName);
        if (v != null) {
          values.add(v);
        }
      }
      return values;
    }

    @Override
    public Optional<Row> parentRow(String resourceName) {
      return Optional.ofNullable(parents.get(resourceName));
    }

    @Override
    public Optional<Row> parentRow() {
      return parents.size() == 1 ? Optional.of(parents.values().iterator().next()) : Optional.empty();
    }

    @Override
    public boolean isGenerated(String fieldName) {
      return row.isGenerated(fieldName);
    }
  }
}
