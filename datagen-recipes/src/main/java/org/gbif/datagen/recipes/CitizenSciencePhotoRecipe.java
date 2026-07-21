// datagen-recipes/src/main/java/org/gbif/datagen/recipes/CitizenSciencePhotoRecipe.java
package org.gbif.datagen.recipes;

import org.gbif.datagen.dwcdp.DwcGen;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;

/** iNaturalist-style photo observations. Exercises the deliberately tricky occurrence-media /
 *  media-identifier joins: photos are reused across sightings (many-to-many, not 1:1), and a
 *  media row can carry zero, one, or several external identifiers.
 *
 *  <pre>
 *                event (8,000)
 *               observation sessions
 *                      │
 *                      │ event_fk  (zipf 1.4, max 15)
 *                      ▼
 *              occurrence (~48,000)
 *               individual sightings
 *                      │
 *                      │ occurrence_fk  (uniform 0-2 photos)
 *                      ▼
 *           occurrence-media (~48,000)
 *              photo-to-sighting links
 *                      │
 *                      │ media_fk  (reused across sightings)
 *                      ▼
 *                media (~15,000)
 *               uploaded photographs
 *                      │
 *                      │ media_fk  (uniform 0-2)
 *                      ▼
 *         media-identifier (~15,000)
 *          external photo-service IDs
 *  </pre>
 *
 *  */
public final class CitizenSciencePhotoRecipe {

  private CitizenSciencePhotoRecipe() {
  }

  public static DataPackageSpec build(SchemaBundle bundle) {
    return Blueprint.forSchema(bundle)
      .global(Key.termOfField(bundle, "event", "decimalLatitude"),
              DwcGen.area(DwcGen.Area.KENYA).latitude().noise(0.1))
      .global(Key.termOfField(bundle, "event", "decimalLongitude"),
              DwcGen.area(DwcGen.Area.KENYA).longitude().noise(0.1))

      .resource("event")
      .allFields()
      .rows(8_000)
      .field("eventType", Gen.constant("opportunistic observation"))
      .field("eventDate", DwcGen.dates().between(2020, 2026)
        .formats(DwcGen.DateFormat.ISO_DAY, DwcGen.DateFormat.ISO_DATETIME))

      .resource("occurrence")
      .allFields()
      .per("event", Dist.zipf(1.4).max(15))
      .field("scientificName", DwcGen.scientificName())
      .field("recordedBy", DwcGen.personName())
      .field("occurrenceStatus", Gen.constant("present"))

      .resource("media")
      .allFields()
      .rows(9_000)
      .field("mediaType", Gen.constant("StillImage"))
      .field("format", Gen.weighted("image/jpeg", 0.85, "image/png", 0.15))

      .resource("occurrence-media")
      .allFields()
      .per("occurrence", Dist.uniform(0, 2))
      // media_fk left to the schema-fk auto default: a real lookup into the media pool,
      // with replacement — so the same photo can legitimately illustrate more than one
      // sighting from the same session, which is the shape this recipe exists to produce.

      .resource("media-identifier")
      .allFields()
      .per("media", Dist.uniform(0, 2))
      .field("identifierType", Gen.constant("URI"))
      .field("identifier", Gen.uuid().map(id -> "https://collections.example.org/media/" + id)) // museum
      .build();
  }

  public static void main(String[] args) {
    RecipeMain.run(args, "citizen-science-photo", CitizenSciencePhotoRecipe::build);
  }
}
