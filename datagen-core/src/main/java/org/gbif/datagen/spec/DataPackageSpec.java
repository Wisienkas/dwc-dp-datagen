package org.gbif.datagen.spec;

import java.util.List;
import java.util.Optional;

import org.gbif.datagen.engine.Engine;
import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.schema.SchemaBundle;

/** A validated, immutable package description. Generating is a pure function of this plus a seed. */
public record DataPackageSpec(SchemaBundle bundle, List<ResourceSpec> resources,
                              List<String> generationOrder, boolean checkConstraints) {

  public ResourceSpec resource(String name) {
    return resources.stream().filter(r -> r.name().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No resource '" + name + "' in this package"));
  }

  public Optional<String> profileUrl() {
    return bundle.profileUrl();
  }

  /** Generates the package. Same spec plus same seed always yields byte-identical output. */
  public GeneratedPackage generate(long seed) {
    return new Engine(this).run(seed);
  }
}
