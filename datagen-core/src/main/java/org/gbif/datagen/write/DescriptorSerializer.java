package org.gbif.datagen.write;

import org.gbif.datagen.spec.DataPackageSpec;

/**
 * Serializes a {@link DataPackageSpec} to a {@code datapackage.json} string.
 */
@FunctionalInterface
public interface DescriptorSerializer {

  String serialize(DataPackageSpec spec, String id, String name, String created);
}
