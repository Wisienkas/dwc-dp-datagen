package org.gbif.datagen.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gbif.datagen.json.JsonSupport;
import org.gbif.datagen.schema.Field;
import org.gbif.datagen.schema.ForeignKey;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.RefSpec;
import org.gbif.datagen.spec.ResourceSpec;

/**
 * Generic Frictionless descriptor emission: field descriptors copied verbatim from the loaded
 * schema, so nothing enumerates known properties and nothing can silently drop a custom one.
 * This is the fallback for contexts with no richer descriptor model on the classpath; a real
 * DwC-DP context should prefer a serializer built on {@code org.gbif.dp.descriptor} instead,
 * since that model can represent {@code weakPrimaryKey}/{@code weakForeignKeys} as first-class
 * properties rather than leaving them inside an opaque raw map.
 */
public final class MapDescriptorSerializer implements DescriptorSerializer {

  @Override
  public String serialize(DataPackageSpec spec, String id, String name, String created) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", name);
    spec.profileUrl().ifPresent(url -> root.put("profile", url));
    root.put("id", id);
    root.put("created", created);

    List<Object> resources = new ArrayList<>();
    for (ResourceSpec resource : spec.resources()) {
      resources.add(resourceObject(spec, resource));
    }
    root.put("resources", resources);
    return JsonSupport.writePretty(root);
  }

  private Map<String, Object> resourceObject(DataPackageSpec spec, ResourceSpec resource) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", resource.name());
    root.put("path", resource.name() + ".csv");
    root.put("profile", "tabular-data-resource");
    root.put("format", "csv");
    root.put("mediatype", "text/csv");
    root.put("schema", schemaObject(spec, resource));
    return root;
  }

  private Map<String, Object> schemaObject(DataPackageSpec spec, ResourceSpec resource) {
    Map<String, Object> schema = new LinkedHashMap<>();

    List<Object> fields = new ArrayList<>();
    for (Field f : resource.fields()) {
      fields.add(f.raw());
    }
    schema.put("fields", fields);

    Set<String> selected = new LinkedHashSet<>(resource.fieldNames());

    List<String> pk = resource.schema().primaryKey().stream().filter(selected::contains).toList();
    if (pk.size() == 1) {
      schema.put("primaryKey", pk.get(0));
    } else if (!pk.isEmpty()) {
      schema.put("primaryKey", pk);
    }

    List<Object> foreignKeys = new ArrayList<>();
    Set<String> emitted = new LinkedHashSet<>();

    for (ForeignKey fk : resource.schema().allForeignKeys()) {
      if (!selected.contains(fk.field())) {
        continue;
      }
      if (fk.hasTarget() && !inPackage(spec, fk.refResource(), fk.refField())) {
        continue;
      }
      if (undeclaredInRecipe(resource, fk.field())) {
        continue;
      }
      foreignKeys.add(fk.raw());
      emitted.add(fk.field());
    }

    for (RefSpec ref : resource.refs()) {
      if (!ref.declared() || emitted.contains(ref.field()) || !selected.contains(ref.field())) {
        continue;
      }
      if (resource.schema().allForeignKeys().stream().anyMatch(fk -> fk.field().equals(ref.field()))) {
        continue;
      }
      Map<String, Object> fkObj = new LinkedHashMap<>();
      fkObj.put("fields", ref.field());
      if (ref.predicate() != null) {
        fkObj.put("predicate", ref.predicate());
      }
      Map<String, Object> reference = new LinkedHashMap<>();
      reference.put("resource", ref.targetResource().equals(resource.name()) ? "" : ref.targetResource());
      reference.put("fields", ref.targetField());
      fkObj.put("reference", reference);
      foreignKeys.add(fkObj);
    }

    if (!foreignKeys.isEmpty()) {
      schema.put("foreignKeys", foreignKeys);
    }
    return schema;
  }

  private boolean undeclaredInRecipe(ResourceSpec resource, String field) {
    return resource.refs().stream().anyMatch(r -> r.field().equals(field) && !r.declared());
  }

  private boolean inPackage(DataPackageSpec spec, String resourceName, String fieldName) {
    return spec.resources().stream()
      .filter(r -> r.name().equals(resourceName))
      .anyMatch(r -> r.fieldNames().contains(fieldName));
  }
}
