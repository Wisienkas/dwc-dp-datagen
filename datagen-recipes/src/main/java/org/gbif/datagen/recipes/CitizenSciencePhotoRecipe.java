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
 *  <p>Also carries a light occurrence-assertion / protocol pairing so
 *  {@code AssertionExtensionBuilder}'s protocol-resolution join (assertionProtocol_fk ->
 *  protocol.protocolDescription -> measurementMethod) gets exercised by this dataset too, not
 *  just its no-protocol-table fallback (renaming the raw FK straight to measurementMethod).
 *
 *  <pre>
 *  event (8,000)
 *   observation sessions
 *          │
 *          │ event_fk  (zipf 1.4, max 15)
 *          ▼
 *  occurrence (~48,000)
 *   individual sightings
 *      ┌────┴─────────────────┐
 *      │ occurrence_fk        │ occurrence_fk
 *      │ (uniform 0-2 photos) │ (uniform 0-2 assertions)
 *      ▼                      ▼
 *  occurrence-media       occurrence-assertion (~48,000)
 *  (~48,000)                body-length measurements
 *   photo-to-sighting            │
 *   links                        │ assertionProtocol_fk (80% resolved, 20% null)
 *      │                         ▼
 *      │ media_fk            protocol (4)
 *      │ (reused across       measurement methods
 *      │  sightings)
 *      ▼
 *  media (~15,000)
 *   uploaded photographs
 *      │
 *      │ media_fk  (uniform 0-2)
 *      ▼
 *  media-identifier (~15,000)
 *   external photo-service IDs
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
      // eventID is a weakPk but not req+unique. Kept explicit rather than relying on the
      // schema-default placeholder, since this field feeds GBIF ID assignment downstream —
      // not a confirmed collision, just not a field worth leaving to undocumented defaults.
      .field("eventID", Gen.uuid())
      .field("eventType", Gen.constant("opportunistic observation"))
      .field("eventDate", DwcGen.dates().between(2020, 2026)
        .formats(DwcGen.DateFormat.ISO_DAY, DwcGen.DateFormat.ISO_DATETIME))

      .resource("occurrence")
      .allFields()
      .per("event", Dist.zipf(1.4).max(15))
      .field("scientificName", DwcGen.scientificName())
      .field("recordedBy", DwcGen.personName())
      .field("occurrenceStatus", Gen.constant("present"))
      // Same note as eventID — kept explicit since occurrenceID feeds GBIF ID assignment,
      // not because of a confirmed collision.
      .field("occurrenceID", Gen.uuid())

      .resource("media")
      .allFields()
      // was 9_000 — didn't match the ~15,000 this class's own doc (and the reuse ratio the
      // recipe is meant to demonstrate) claimed. Fixed to match.
      .rows(15_000)
      .field("mediaID", Gen.uuid()) // same weakPk-not-req+unique gap as eventID
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

      // Small, standardized measurement-method vocabulary — real citizen-science pipelines
      // resolve a handful of protocols, not one bespoke protocol per assertion.
      .resource("protocol")
      .allFields()
      .rows(4)
      .field("protocolID", Gen.uuid()) // same weakPk-not-req+unique gap as eventID
      .field("protocolType", Gen.constant("measurement"))
      .field("protocolName", Gen.sample(
        "iNaturalist community size-estimate protocol",
        "Photo scale-bar measurement",
        "Crowd-sourced identification confidence score",
        "EXIF-derived body length estimate"))

      .resource("occurrence-assertion")
      .allFields()
      .per("occurrence", Dist.uniform(0, 2))
      // assertionID is uniq but NOT required — under .allFields() the schema default for a
      // non-required-unique field is a repeated deterministic placeholder, which would trip
      // the uniqueness constraint check the moment more than one assertion row exists. Needs
      // an explicit generator.
      .field("assertionID", Gen.uuid())
      .field("assertionType", Gen.constant("estimated body length"))
      .field("assertionUnit", Gen.constant("mm"))
      // Gen.between(min, max, decimals) always returns a Double, even at decimals=0 (the
      // rounding step promotes via double division) — .map(String::valueOf) on that gives
      // "205.0", not "205". Round explicitly instead.
      .field("assertionValue", Gen.between(2, 400).map(v -> String.valueOf(Math.round(v.doubleValue()))))
      // Left with a nullChance so both branches of AssertionExtensionBuilder's protocol join
      // get real data: resolved (protocol present -> measurementMethod populated) and the
      // dangling case (null FK -> measurementMethod stays null after the left join).
      .field("assertionProtocol_fk", Gen.lookup("protocol", "protocol_pk").nullChance(0.2))
      .build();
  }

  public static void main(String[] args) {
    RecipeMain.run(args, "citizen-science-photo", CitizenSciencePhotoRecipe::build);
  }
}
