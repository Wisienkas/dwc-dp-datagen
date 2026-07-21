package org.gbif.datagen.recipes;

import org.gbif.datagen.dwcdp.DwcGen;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;

/** Bird-banding station data: event -> occurrence, with a minority of occurrences linking back
 *  to a previously banded individual via organism_fk (recaptures).
 * <pre>
 *               event (3,000)
 *              site visits / net checks
 *                      │
 *                      │ event_fk  (zipf 1.3, max 40 per event)
 *                      ▼
 *               occurrence (~55,000)
 *              every banding-station record
 *                      │
 *                      │ organism_fk (30% populated — recaptures only)
 *                      ▼
 *                organism (8,000)
 *               individually tagged birds
 * </pre>
 *
 */
public final class BirdBandingRecipe {

  private BirdBandingRecipe() {
  }

  public static DataPackageSpec build(SchemaBundle bundle) {
    return Blueprint.forSchema(bundle)
      .global(Key.termOfField(bundle, "event", "decimalLatitude"),
              DwcGen.area(DwcGen.Area.DENMARK).latitude().noise(0.05))
      .global(Key.termOfField(bundle, "event", "decimalLongitude"),
              DwcGen.area(DwcGen.Area.DENMARK).longitude().noise(0.05))

      .resource("event")
      .allFields()
      .rows(3_000)
      .field("eventType", Gen.weighted("banding station check", 0.7, "mist-net survey", 0.3))
      .field("eventDate", DwcGen.dates().between(2018, 2025).formats(DwcGen.DateFormat.ISO_DAY))
      .field("habitat", Gen.<String>sample(
          java.util.List.of("reedbed", "coastal scrub", "mixed woodland", "farmland hedgerow"))
        .nullChance(0.2))
      .field("fieldNotes", Gen.constant("Constant Effort Site, standard mist-net array"))

      .resource("organism")
      .allFields()
      .rows(8_000)
      .field("organismScope", Gen.constant("individual"))
      .field("organismName", Gen.uuid().map(id -> "BAND-" + id.substring(0, 8)).nullChance(0.1))

      .resource("occurrence")
      .allFields()
      .per("event", Dist.zipf(1.3).max(40))
      .field("scientificName", DwcGen.scientificName())
      .field("sex", Gen.weighted("Male", 0.42, "Female", 0.42, "Undetermined", 0.16))
      .field("lifeStage", Gen.weighted("Adult", 0.6, "Juvenile", 0.3, "Hatch-year", 0.1))
      .field("recordedBy", DwcGen.personNames(2))
      // schema-fk auto default has no null-modeling; override explicitly so most captures
      // are first-captures, not recaptures.
      .field("organismID", Gen.lookup("organism", "organism_pk").nullChance(0.7))
      .build();
  }

  public static void main(String[] args) {
    RecipeMain.run(args, "bird-banding", BirdBandingRecipe::build);
  }
}
