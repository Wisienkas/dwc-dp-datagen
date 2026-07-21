package org.gbif.datagen.write;

import org.gbif.datagen.spec.DataPackageSpec;

/**
 * Serializes a {@link DataPackageSpec} to a {@code datapackage.json} string.
 *
 * <p>Core ships one generic implementation ({@link MapDescriptorSerializer}) that emits a plain
 * property-map descriptor — enough to be valid Frictionless, and enough for tests that use a
 * bare {@link org.gbif.datagen.schema.SchemaBundle} with no real DwC-DP model on the classpath.
 * A DwC-DP context should supply a serializer built on the real
 * {@code org.gbif.dp.descriptor} model instead, via {@link PackageWriter#descriptorSerializer}.
 */
@FunctionalInterface
public interface DescriptorSerializer {

  String serialize(DataPackageSpec spec, String id, String created);
}
