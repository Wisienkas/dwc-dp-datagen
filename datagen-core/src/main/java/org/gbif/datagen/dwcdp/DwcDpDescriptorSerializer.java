package org.gbif.datagen.dwcdp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.gbif.datagen.schema.Field;
import org.gbif.datagen.schema.ForeignKey;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.RefSpec;
import org.gbif.datagen.spec.ResourceSpec;
import org.gbif.datagen.write.DescriptorSerializer;

import org.gbif.dp.descriptor.DataPackageDescriptor;
import org.gbif.dp.descriptor.FieldConstraints;
import org.gbif.dp.descriptor.FieldDescriptor;
import org.gbif.dp.descriptor.ForeignKeyDescriptor;
import org.gbif.dp.descriptor.MissingValueDescriptor;
import org.gbif.dp.descriptor.PrimaryKeyDescriptor;
import org.gbif.dp.descriptor.ReferenceDescriptor;
import org.gbif.dp.descriptor.ResourceDescriptor;
import org.gbif.dp.descriptor.SchemaDescriptor;

/**
 * Builds a real {@code org.gbif.dp.descriptor} model from a {@link DataPackageSpec} and
 * serializes it with Jackson, instead of hand-assembling a property map.
 *
 * <p>{@code FieldDescriptor}/{@code ForeignKeyDescriptor}/etc. are plain records with no Jackson
 * annotations, so a few wire-shape decisions (single-vs-array fields, {@code dcterms:} property
 * names, bare-string {@code missingValues}) are supplied here via mixins and custom serializers
 * rather than by editing the model itself. See the class-level assumptions called out in the
 * accompanying message — verify against one real published 1.0_DEV descriptor before trusting
 * this blindly.
 */
public final class DwcDpDescriptorSerializer implements DescriptorSerializer {

  private static final ObjectMapper MAPPER = buildMapper();

  @Override
  public String serialize(DataPackageSpec spec, String id, String name, String created) {
    List<ResourceDescriptor> resources = new ArrayList<>();
    for (ResourceSpec resource : spec.resources()) {
      resources.add(resourceDescriptor(spec, resource));
    }

    DataPackageDescriptor descriptor = DataPackageDescriptor.builder()
      .id(id)
      .name(name)
      .created(created)
      .profile(spec.profileUrl().orElse(null))
      .resources(resources)
      .build();

    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(descriptor);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed serializing datapackage.json", (java.io.IOException) e);
    }
  }

  private ResourceDescriptor resourceDescriptor(DataPackageSpec spec, ResourceSpec resource) {
    return ResourceDescriptor.builder()
      .name(resource.name())
      .paths(List.of(resource.name() + ".csv"))
      .profile("tabular-data-resource")
      .format("csv")
      .mediatype("text/csv")
      .schema(schemaDescriptor(spec, resource))
      .build();
  }

  private SchemaDescriptor schemaDescriptor(DataPackageSpec spec, ResourceSpec resource) {
    List<String> selected = resource.fieldNames();

    List<FieldDescriptor> fields = new ArrayList<>();
    for (Field f : resource.fields()) {
      fields.add(fieldDescriptor(f));
    }

    List<String> strongPk = intersect(resource.schema().primaryKey(), selected);
    List<String> weakPk = intersect(resource.schema().weakPrimaryKey(), selected);

    List<ForeignKeyDescriptor> strongFks = new ArrayList<>();
    List<ForeignKeyDescriptor> weakFks = new ArrayList<>();
    Set<String> emitted = new java.util.LinkedHashSet<>();

    for (ForeignKey fk : resource.schema().foreignKeys()) {
      if (accept(spec, resource, selected, fk, emitted)) {
        strongFks.add(foreignKeyDescriptor(fk, resource.name()));
      }
    }
    for (ForeignKey fk : resource.schema().weakForeignKeys()) {
      if (accept(spec, resource, selected, fk, emitted)) {
        weakFks.add(foreignKeyDescriptor(fk, resource.name()));
      }
    }

    return SchemaDescriptor.builder()
      .fields(fields)
      .primaryKey(strongPk.isEmpty() ? null : new PrimaryKeyDescriptor(strongPk))
      .weakPrimaryKey(weakPk.isEmpty() ? null : new PrimaryKeyDescriptor(weakPk))
      .foreignKeys(strongFks)
      .weakForeignKeys(weakFks)
      .build();
  }

  private boolean accept(DataPackageSpec spec, ResourceSpec resource, List<String> selected,
                         ForeignKey fk, Set<String> emitted) {
    if (!selected.contains(fk.field())) {
      return false;
    }
    if (fk.hasTarget() && !inPackage(spec, fk.refResource(), fk.refField())) {
      return false;
    }
    if (resource.refs().stream().anyMatch(r -> r.field().equals(fk.field()) && !r.declared())) {
      return false;
    }
    emitted.add(fk.field());
    return true;
  }

  private ForeignKeyDescriptor foreignKeyDescriptor(ForeignKey fk, String owningResource) {
    if (!fk.hasTarget()) {
      return ForeignKeyDescriptor.builder()
        .fields(List.of(fk.field()))
        .predicate(fk.predicate())
        .build(); // reference left null — a targetless weak FK, not a malformed one
    }
    String resourceProperty = fk.refResource().equals(owningResource) ? "" : fk.refResource();
    return ForeignKeyDescriptor.builder()
      .fields(List.of(fk.field()))
      .reference(new ReferenceDescriptor(resourceProperty, List.of(fk.refField())))
      .predicate(fk.predicate())
      .build();
  }

  private FieldDescriptor fieldDescriptor(Field f) {
    FieldConstraints constraints = FieldConstraints.builder()
      .required(f.required())
      .unique(f.unique())
      .minLength(f.minLength().orElse(null))
      .maxLength(f.maxLength().orElse(null))
      .pattern(f.pattern().orElse(null))
      .enumValues(f.enumValues().map(DwcDpDescriptorSerializer::asStrings).orElse(null))
      .minimum(f.minimum().orElse(null))
      .maximum(f.maximum().orElse(null))
      .build();

    return FieldDescriptor.builder()
      .name(f.name())
      .type(f.type())
      .format(f.format().orElse("default"))
      .missingValues(f.missingValues()
                       .map(DwcDpDescriptorSerializer::asMissingValues)
                       .orElse(List.of(MissingValueDescriptor.NULL)))
      .constraints(constraints)
      .title(f.title().orElse(null))
      .description(f.description().orElse(null))
      .dctermsIsVersionOf(f.isVersionOf().orElse(null))
      .dctermsReferences(f.references().orElse(null))
      .build();
  }

  private static List<String> asStrings(List<Object> values) {
    return values.stream().map(String::valueOf).toList();
  }

  private static List<MissingValueDescriptor> asMissingValues(List<Object> values) {
    return values.stream()
      .map(v -> new MissingValueDescriptor(String.valueOf(v), MissingValueDescriptor.Source.FIELD))
      .toList();
  }

  private static List<String> intersect(List<String> keys, List<String> selected) {
    return keys.stream().filter(selected::contains).toList();
  }

  private boolean inPackage(DataPackageSpec spec, String resourceName, String fieldName) {
    return spec.resources().stream()
      .filter(r -> r.name().equals(resourceName))
      .anyMatch(r -> r.fieldNames().contains(fieldName));
  }

  // ---------------------------------------------------------------- Jackson wiring

  private static ObjectMapper buildMapper() {
    ObjectMapper mapper = new ObjectMapper()
      .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    SimpleModule module = new SimpleModule();
    module.addSerializer(MissingValueDescriptor.class, new MissingValueDescriptorSerializer());
    module.addSerializer(PrimaryKeyDescriptor.class, new PrimaryKeyDescriptorSerializer());
    mapper.registerModule(module);

    mapper.addMixIn(FieldDescriptor.class, FieldDescriptorMixin.class);
    mapper.addMixIn(FieldConstraints.class, FieldConstraintsMixin.class);
    mapper.addMixIn(ForeignKeyDescriptor.class, ForeignKeyDescriptorMixin.class);
    mapper.addMixIn(ReferenceDescriptor.class, ReferenceDescriptorMixin.class);
    mapper.addMixIn(ResourceDescriptor.class, ResourceDescriptorMixin.class);
    return mapper;
  }

  /** Bare string on the wire — {@code source} is our own bookkeeping, not part of the spec. */
  private static final class MissingValueDescriptorSerializer extends JsonSerializer<MissingValueDescriptor> {
    @Override
    public void serialize(MissingValueDescriptor value, JsonGenerator gen, SerializerProvider sp)
      throws IOException {
      gen.writeString(value.rawValue());
    }
  }

  /** Bare string when one key, array when composite — every example shown uses the singular form. */
  private static final class PrimaryKeyDescriptorSerializer extends JsonSerializer<PrimaryKeyDescriptor> {
    @Override
    public void serialize(PrimaryKeyDescriptor value, JsonGenerator gen, SerializerProvider sp)
      throws IOException {
      writeSingleOrList(value.keys(), gen);
    }
  }

  private static void writeSingleOrList(List<String> values, JsonGenerator gen) throws IOException {
    if (values == null || values.isEmpty()) {
      gen.writeNull();
    } else if (values.size() == 1) {
      gen.writeString(values.get(0));
    } else {
      gen.writeStartArray();
      for (String v : values) {
        gen.writeString(v);
      }
      gen.writeEndArray();
    }
  }

  private abstract static class FieldDescriptorMixin {
    @JsonProperty("dcterms:isVersionOf")
    abstract String dctermsIsVersionOf();

    @JsonProperty("dcterms:references")
    abstract String dctermsReferences();
  }

  private abstract static class FieldConstraintsMixin {
    @JsonProperty("enum")
    abstract List<String> enumValues();
  }

  private abstract static class ForeignKeyDescriptorMixin {
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = SingleOrListSerializer.class)
    abstract List<String> fields();
  }

  private abstract static class ReferenceDescriptorMixin {
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = SingleOrListSerializer.class)
    abstract List<String> fields();

    // Overrides the class's NON_EMPTY default for this one property. An empty string here is the
    // Frictionless self-reference sentinel, not an absent value — NON_EMPTY would otherwise drop
    // it from the wire entirely, silently turning a self-reference into an indistinguishable
    // "no resource specified."
    @JsonInclude(JsonInclude.Include.ALWAYS)
    abstract String resource();
  }

  private abstract static class ResourceDescriptorMixin {
    @JsonProperty("path")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = SingleOrListSerializer.class)
    abstract List<String> paths();
  }

  private static final class SingleOrListSerializer extends JsonSerializer<List<String>> {
    @Override
    public void serialize(List<String> value, JsonGenerator gen, SerializerProvider sp) throws IOException {
      writeSingleOrList(value, gen);
    }
  }
}
