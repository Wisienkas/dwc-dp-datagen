package org.gbif.datagen.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.FieldReference;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.gen.Generator;
import org.gbif.datagen.schema.Field;
import org.gbif.datagen.schema.ForeignKey;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.schema.TableSchema;

/**
 * Fluent entry point for describing a data package to generate.
 *
 * <pre>{@code
 * Blueprint.forSchema(bundle)
 *     .global(Key.termOfField(bundle, "event", "decimalLatitude"), area(DENMARK).noise(0.01))
 *     .resource("event")
 *         .allFields()
 *         .rows(150)
 *         .field("eventDate", dates().between(2015, 2025))
 *         .selfRef("parentEvent_fk", "event_pk").roots(0.15)
 *     .resource("occurrence")
 *         .per("event", Dist.zipf(1.2).max(200))
 *         .field("occurrenceStatus", weighted("present", .85, "absent", .15))
 *     .build();
 * }</pre>
 */
public final class Blueprint {

  private final SchemaBundle bundle;
  private final Map<Key, Generator<?>> globals = new LinkedHashMap<>();
  private final List<ResourceBuilder> resources = new ArrayList<>();
  private boolean checkConstraints = true;

  private Blueprint(SchemaBundle bundle) {
    this.bundle = bundle;
  }

  public static Blueprint forSchema(SchemaBundle bundle) {
    return new Blueprint(bundle);
  }

  public Blueprint global(Key key, Generator<?> generator) {
    globals.put(key, generator);
    return this;
  }

  public Blueprint checkConstraints(boolean check) {
    this.checkConstraints = check;
    return this;
  }

  public ResourceBuilder resource(String name) {
    TableSchema schema = bundle.requireSchema(name);
    ResourceBuilder rb = new ResourceBuilder(this, name, schema);
    resources.add(rb);
    return rb;
  }

  SchemaBundle bundle() {
    return bundle;
  }

  /** Whether a resource of this name is part of the package being built. */
  boolean hasResource(String name) {
    return resources.stream().anyMatch(rb -> rb.name().equals(name));
  }

  public DataPackageSpec build() {
    if (resources.isEmpty()) {
      throw new IllegalStateException("No resources declared");
    }

    Set<String> names = new LinkedHashSet<>();
    for (ResourceBuilder rb : resources) {
      if (!names.add(rb.name())) {
        throw new IllegalStateException("Resource '" + rb.name() + "' declared more than once");
      }
    }

    validateRefTargets(names);
    Map<String, Set<String>> forced = computeForcedFields(names);

    List<ResourceSpec> specs = new ArrayList<>();
    for (ResourceBuilder rb : resources) {
      specs.add(rb.resolve(globals, forced.getOrDefault(rb.name(), Set.of())));
    }

    List<String> order = TopoSort.order(specs);
    return new DataPackageSpec(bundle, specs, order, checkConstraints);
  }

  /** Refs must point at a table in the package and at a field that table actually has. */
  private void validateRefTargets(Set<String> declaredResources) {
    for (ResourceBuilder rb : resources) {
      for (RefSpec ref : rb.refs()) {
        if (!declaredResources.contains(ref.targetResource())) {
          throw new IllegalStateException("Resource '" + rb.name() + "' references table '"
                                          + ref.targetResource() + "', which is not part of this package. Declared: " + declaredResources);
        }
        TableSchema targetSchema = bundle.requireSchema(ref.targetResource());
        if (!targetSchema.hasField(ref.targetField())) {
          throw new IllegalStateException("Resource '" + rb.name() + "' references '"
                                          + ref.targetResource() + "." + ref.targetField() + "', but table '" + ref.targetResource()
                                          + "' has no such field." + FieldSelection.suggest(ref.targetField(), targetSchema.fieldNames()));
        }
        if (!rb.schema().hasField(ref.field())) {
          throw new IllegalStateException("Resource '" + rb.name() + "' declares a ref on field '"
                                          + ref.field() + "', which table '" + rb.name() + "' does not have."
                                          + FieldSelection.suggest(ref.field(), rb.schema().fieldNames()));
        }
        checkAgainstSchema(rb, ref);
      }
    }
  }

  private void checkAgainstSchema(ResourceBuilder rb, RefSpec ref) {
    for (ForeignKey fk : rb.schema().allForeignKeys()) {
      if (!fk.hasTarget() || !fk.field().equals(ref.field())) {
        continue;
      }
      boolean sameTarget = fk.refResource().equals(ref.targetResource())
                           && fk.refField().equals(ref.targetField());
      if (!sameTarget) {
        throw new IllegalStateException("Resource '" + rb.name() + "': ref on '" + ref.field()
                                        + "' points at " + ref.targetResource() + "." + ref.targetField()
                                        + " but the schema declares a foreign key on the same field pointing at "
                                        + fk.refResource() + "." + fk.refField() + ". Refusing to contradict the schema.");
      }
    }
  }

  private Map<String, Set<String>> computeForcedFields(Set<String> declaredResources) {
    Map<String, Set<String>> forced = new LinkedHashMap<>();
    for (String name : declaredResources) {
      forced.put(name, new LinkedHashSet<>());
    }

    for (ResourceBuilder rb : resources) {
      Set<String> own = forced.get(rb.name());

      rb.schema().requiredFields().forEach(f -> own.add(f.name()));
      own.addAll(rb.explicitGeneratorFields());

      for (RefSpec ref : rb.refs()) {
        own.add(ref.field());
        forced.get(ref.targetResource()).add(ref.targetField());
      }

      for (ForeignKey fk : rb.schema().allForeignKeys()) {
        if (fk.hasTarget() && declaredResources.contains(fk.refResource()) && rb.willInclude(fk.field())) {
          own.add(fk.field());
          forced.get(fk.refResource()).add(fk.refField());
        }
      }

      rb.anchor().ifPresent(a -> {
        TableSchema parentSchema = bundle.requireSchema(a.parentResource());
        parentSchema.primaryKey().forEach(pk -> forced.get(a.parentResource()).add(pk));
      });
    }
    return forced;
  }

  /** Per-resource fluent builder. */
  public static final class ResourceBuilder {

    private final Blueprint parent;
    private final String name;
    private final TableSchema schema;
    private final FieldSelection selection = new FieldSelection();
    private final Map<String, Generator<?>> generators = new LinkedHashMap<>();
    private final List<RefSpec> refs = new ArrayList<>();
    private Integer rowCount;
    private ResourceSpec.Anchor anchor;
    private Double selfRefRoots;
    private String selfRefField;

    ResourceBuilder(Blueprint parent, String name, TableSchema schema) {
      this.parent = parent;
      this.name = name;
      this.schema = schema;
    }

    public ResourceBuilder allFields() {
      selection.mode(FieldSelection.Mode.ALL);
      return this;
    }

    public ResourceBuilder requiredFields() {
      selection.mode(FieldSelection.Mode.REQUIRED);
      return this;
    }

    public ResourceBuilder fields(String... fields) {
      selection.explicit(List.of(fields));
      return this;
    }

    public ResourceBuilder with(String... fields) {
      selection.add(List.of(fields));
      return this;
    }

    public ResourceBuilder without(String... fields) {
      selection.remove(List.of(fields));
      return this;
    }

    public ResourceBuilder rows(int count) {
      if (anchor != null) {
        throw new IllegalStateException("Resource '" + name + "': rows() and per() are mutually exclusive");
      }
      if (count < 0) {
        throw new IllegalArgumentException("rows(" + count + "): must be non-negative");
      }
      this.rowCount = count;
      return this;
    }

    public ResourceBuilder per(String parentResource, Dist dist) {
      if (rowCount != null) {
        throw new IllegalStateException("Resource '" + name + "': rows() and per() are mutually exclusive");
      }
      this.anchor = new ResourceSpec.Anchor(parentResource, dist);
      return this;
    }

    /** Bind a generator. Also selects the field, so generators double as selection. */
    public ResourceBuilder field(String fieldName, Generator<?> generator) {
      if (!schema.hasField(fieldName)) {
        throw new IllegalArgumentException("Table '" + name + "' has no field '" + fieldName + "'."
                                           + FieldSelection.suggest(fieldName, schema.fieldNames()));
      }
      generators.put(fieldName, generator);
      return this;
    }

    public RefBuilder ref(String field, String targetResource, String targetField) {
      RefSpec spec = RefSpec.of(field, targetResource, targetField);
      refs.add(spec);
      return new RefBuilder(this, refs.size() - 1);
    }

    public ResourceBuilder selfRef(String field, String targetField) {
      refs.add(RefSpec.of(field, name, targetField));
      this.selfRefField = field;
      return this;
    }

    public ResourceBuilder roots(double fraction) {
      if (selfRefField == null) {
        throw new IllegalStateException("Resource '" + name + "': roots() requires a selfRef()");
      }
      if (fraction < 0 || fraction > 1) {
        throw new IllegalArgumentException("roots must be in [0,1], got " + fraction);
      }
      this.selfRefRoots = fraction;
      return this;
    }

    public ResourceBuilder resource(String other) {
      return parent.resource(other);
    }

    public Blueprint global(Key key, Generator<?> generator) {
      return parent.global(key, generator);
    }

    public DataPackageSpec build() {
      return parent.build();
    }

    String name() {
      return name;
    }

    TableSchema schema() {
      return schema;
    }

    List<RefSpec> refs() {
      return refs;
    }

    Optional<ResourceSpec.Anchor> anchor() {
      return Optional.ofNullable(anchor);
    }

    Set<String> explicitGeneratorFields() {
      return generators.keySet();
    }

    void replaceRef(int index, RefSpec spec) {
      refs.set(index, spec);
    }

    boolean willInclude(String fieldName) {
      return switch (selection.mode()) {
        case ALL -> true;
        case REQUIRED -> schema.field(fieldName).map(Field::required).orElse(false)
                         || generators.containsKey(fieldName);
        case EXPLICIT -> generators.containsKey(fieldName)
                         || schema.field(fieldName).map(Field::required).orElse(false);
      };
    }

    ResourceSpec resolve(Map<Key, Generator<?>> globals, Set<String> forced) {
      if (rowCount == null && anchor == null) {
        throw new IllegalStateException("Resource '" + name + "': needs rows(n) or per(parent, dist)");
      }

      List<Field> fields = selection.resolve(schema, forced, name);
      Set<String> selected = new LinkedHashSet<>(fields.stream().map(Field::name).toList());

      Map<String, Generator<?>> resolved = new LinkedHashMap<>();
      for (Field f : fields) {
        resolved.put(f.name(), resolveGenerator(f, globals, selected));
      }

      validateFieldReferences(resolved, selected);

      return new ResourceSpec(name, schema, fields, Map.copyOf(resolved), List.copyOf(refs),
                              Optional.ofNullable(rowCount), Optional.ofNullable(anchor));
    }

    /**
     * Catches exactly the failure mode that motivated this: a template()/deriveAfter() referencing
     * a field name that is wrong or was never selected. Without this, it surfaces at generation
     * time as "no further progress" — indistinguishable from a genuine cycle, and much harder to
     * debug than a build()-time "did you mean" error naming the actual field and generator at fault.
     */
    private void validateFieldReferences(Map<String, Generator<?>> resolved, Set<String> selected) {
      for (Map.Entry<String, Generator<?>> e : resolved.entrySet()) {
        if (!(e.getValue() instanceof FieldReference fr)) {
          continue;
        }
        for (String ref : fr.referencedFields()) {
          if (!selected.contains(ref)) {
            // Suggest against `selected`, not all schema fields: the requirement here is
            // "must be selected on this resource," not "must exist in the schema somewhere." A
            // real, correctly-spelled schema field that just isn't selected must not be suggested
            // back as if it were a typo — that would send someone fixing a typo that wasn't one.
            String suggestion = FieldSelection.suggest(ref, selected);
            throw new IllegalStateException("Resource '" + name + "': field '" + e.getKey()
                                            + "' references '" + ref + "', which is not among this resource's selected fields "
                                            + selected + "." + suggestion);
          }
        }
      }
    }

    /** Resolution order: field &gt; resource &gt; global(term) &gt; global(type) &gt; schema default. */
    private Generator<?> resolveGenerator(Field field, Map<Key, Generator<?>> globals, Set<String> selected) {
      Generator<?> explicit = generators.get(field.name());
      if (explicit != null) {
        return explicit;
      }

      Optional<RefSpec> ref = refs.stream().filter(r -> r.field().equals(field.name())).findFirst();
      if (ref.isPresent()) {
        return refGenerator(ref.get());
      }

      // A schema-declared FK the recipe never re-declared via .ref(...) still gets the same
      // lookup/self-ref/anchor treatment, provided its target table is part of this package.
      // Without this, a zero-config resource leaves every FK column an unresolved placeholder
      // string — exactly the columns where "schema constraints alone already produce valid data"
      // matters most.
      Optional<ForeignKey> schemaFk = schema.allForeignKeys().stream()
        .filter(fk -> fk.field().equals(field.name())).findFirst();
      if (schemaFk.isPresent() && schemaFk.get().hasTarget()) {
        ForeignKey fk = schemaFk.get();
        boolean targetAvailable = fk.refResource().equals(name) || parent.hasResource(fk.refResource());
        if (targetAvailable) {
          return refGenerator(RefSpec.of(fk.field(), fk.refResource(), fk.refField()));
        }
      }

      for (Map.Entry<Key, Generator<?>> e : globals.entrySet()) {
        if (e.getKey() instanceof Key.TermKey && e.getKey().matches(field)) {
          return e.getValue();
        }
      }
      for (Map.Entry<Key, Generator<?>> e : globals.entrySet()) {
        if (e.getKey() instanceof Key.TypeKey && e.getKey().matches(field)) {
          return e.getValue();
        }
      }

      return SchemaDefaults.forField(field);
    }

    private Generator<?> refGenerator(RefSpec ref) {
      if (ref.isSelfReference(name)) {
        double rootFraction = selfRefRoots == null ? 0.1 : selfRefRoots;
        String targetField = ref.targetField();
        return new SelfRefGenerator(name, targetField, rootFraction);
      }
      if (anchor != null && anchor.parentResource().equals(ref.targetResource())) {
        String targetField = ref.targetField();
        return (Generator<Object>) ctx -> ctx.parentRow(ref.targetResource())
          .map(p -> p.get(targetField))
          .orElse(null);
      }
      return Gen.lookup(ref.targetResource(), ref.targetField());
    }
  }

  public static final class RefBuilder {
    private final ResourceBuilder owner;
    private final int index;

    RefBuilder(ResourceBuilder owner, int index) {
      this.owner = owner;
      this.index = index;
    }

    public RefBuilder declared(boolean declared) {
      owner.replaceRef(index, owner.refs().get(index).declared(declared));
      return this;
    }

    public RefBuilder predicate(String predicate) {
      owner.replaceRef(index, owner.refs().get(index).predicate(predicate));
      return this;
    }

    public ResourceBuilder and() {
      return owner;
    }

    public ResourceBuilder field(String fieldName, Generator<?> generator) {
      return owner.field(fieldName, generator);
    }

    public RefBuilder ref(String field, String targetResource, String targetField) {
      return owner.ref(field, targetResource, targetField);
    }

    public ResourceBuilder resource(String other) {
      return owner.resource(other);
    }

    public DataPackageSpec build() {
      return owner.build();
    }
  }
}
