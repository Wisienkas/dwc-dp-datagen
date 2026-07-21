package org.gbif.datagen.recipes;

import org.gbif.datagen.dwcdp.DwcGen;
import org.gbif.datagen.gen.Dist;
import org.gbif.datagen.gen.Gen;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.spec.Blueprint;
import org.gbif.datagen.spec.DataPackageSpec;
import org.gbif.datagen.spec.Key;

/** Standardized sampling-effort data (the "Humboldt extension" tables): survey/survey-target
 *  describe what was being looked for, so absence can be inferred and not just presence.
 *  Roughly a third of occurrences are opportunistic records from the same events, not part of
 *  any standardized survey — survey_fk is deliberately null for those.
 *  <pre>
 *  event (2,500)                survey (1,800)
 *       site-visit episodes     ┌──event_fk──▶ standardized sampling
 *              │                │                   │
 *              │ event_fk       │                   │ survey_fk
 *              ▼                │                   ▼
 *        occurrence (~30,000) ──┘          survey-target (~5,400)
 *       sighting/absence records          intended scope per survey
 *              │
 *              │ organism_fk (25%)
 *              ▼
 *         organism (4,000)
 *  </pre>
 *  */
public final class HumboldtSurveyRecipe {

  private HumboldtSurveyRecipe() {
  }

  public static DataPackageSpec build(SchemaBundle bundle) {
    return Blueprint.forSchema(bundle)
      .global(Key.termOfField(bundle, "event", "decimalLatitude"),
              DwcGen.area(DwcGen.Area.NORWAY).latitude().noise(0.08))
      .global(Key.termOfField(bundle, "event", "decimalLongitude"),
              DwcGen.area(DwcGen.Area.NORWAY).longitude().noise(0.08))

      .resource("event")
      .allFields()
      .rows(2_500)
      .field("eventType", Gen.weighted("transect walk", 0.5, "camera trap deployment", 0.3,
                                       "BioBlitz", 0.2))
      .field("eventDate", DwcGen.dates().between(2019, 2025).formats(DwcGen.DateFormat.ISO_DAY))
      .field("fieldNotes", Gen.constant("standardized transect, fixed duration"))

      .resource("survey")
      .allFields()
      .rows(1_800)
      .field("event_fk", Gen.lookup("event", "event_pk"))
      .field("samplingEffortProtocol", Gen.constant("standardized transect, fixed duration"))
      .field("isAbundanceReported", Gen.weighted(true, 0.7, false, 0.3))
      .field("isAbsenceReported", Gen.weighted(true, 0.6, false, 0.4))

      .resource("organism")
      .allFields()
      .rows(4_000)
      .field("organismID", Gen.uuid())
      .field("organismScope", Gen.constant("individual"))

      .resource("occurrence")
      .allFields()
      .per("event", Dist.zipf(1.3).max(25))
      .field("scientificName", DwcGen.scientificName())
      .field("occurrenceStatus", Gen.weighted("present", 0.8, "absent", 0.2))
      .field("organismID", Gen.lookup("organism", "organismID").nullChance(0.75))
      .build();
  }

  public static void main(String[] args) {
    RecipeMain.run(args, "humboldt-survey", HumboldtSurveyRecipe::build);
  }
}
