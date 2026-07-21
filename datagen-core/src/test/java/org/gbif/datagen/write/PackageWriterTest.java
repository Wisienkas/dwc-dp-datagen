package org.gbif.datagen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.gbif.datagen.engine.GeneratedPackage;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.smoke.FixtureSchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link PackageWriter} actually delegates to whatever {@link DescriptorSerializer} it's
 * given, rather than being hardcoded to {@link MapDescriptorSerializer} — the exact failure
 * mode a future refactor could quietly reintroduce with no test catching it. Also proves the
 * constructor rejects a null serializer, since a silent default there is precisely the footgun
 * the required-constructor-argument design exists to prevent.
 */
class PackageWriterTest {

  private SchemaBundle bundle() {
    return new FixtureSchemaBundle();
  }

  private GeneratedPackage samplePackage() {
    DataPackageSpec spec = Blueprint.forSchema(bundle())
      .resource("widget").rows(5)
      .build();
    return spec.generate(1);
  }

  @Test
  void constructorRejectsNullSerializer() {
    assertThrows(NullPointerException.class, () -> new PackageWriter(null));
  }

  @Test
  void writeDelegatesToTheSuppliedSerializer() throws Exception {
    FakeSerializer fake = new FakeSerializer();
    Path dir = Files.createTempDirectory("datagen-packagewriter-test");

    new PackageWriter(fake).id("test-id").write(samplePackage(), dir);

    assertTrue(fake.called, "PackageWriter.write must call the supplied serializer");
    String written = Files.readString(dir.resolve("datapackage.json"));
    assertEquals(fake.lastOutput, written, "the file on disk must be exactly what the serializer returned");
    assertTrue(written.contains("test-id"), "the id passed to write() must reach the serializer");
  }

  @Test
  void mapDescriptorSerializerProducesAValidLookingDescriptor() throws Exception {
    Path dir = Files.createTempDirectory("datagen-packagewriter-test-map");
    new PackageWriter(new MapDescriptorSerializer()).write(samplePackage(), dir);

    String written = Files.readString(dir.resolve("datapackage.json"));
    assertTrue(written.contains("\"resources\""), "expected a 'resources' array in the descriptor");
    assertTrue(written.contains("\"widget\""), "expected the widget resource to be present");
  }

  private static final class FakeSerializer implements DescriptorSerializer {
    boolean called = false;
    String lastOutput;

    @Override
    public String serialize(DataPackageSpec spec, String id, String created) {
      called = true;
      lastOutput = "{\"fake\":true,\"id\":\"" + id + "\"}";
      return lastOutput;
    }
  }
}
