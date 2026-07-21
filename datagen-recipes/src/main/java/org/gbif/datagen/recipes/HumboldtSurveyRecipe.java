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
 *
 *  <p>survey-target / survey-survey-target are included specifically to exercise both branches
 *  of {@code HumboldtExtensionBuilder}: {@code Dist.uniform(0, 3)} on the junction table means
 *  roughly a third of surveys get zero survey-target rows (single Humboldt row per event, no
 *  fan-out) while the rest get 1-3 (fan-out — one Humboldt extension row per survey-target,
 *  all sharing the same eventID).
 *
 *  <pre>
 *  event (2,500)              survey (1,800)             survey-target (40)
 *   site-visit episodes   ┌──event_fk──▶ standardized      standardized target
 *          │              │              sampling             definitions
 *          │ event_fk     │                   │                    ▲
 *          ▼              │                   │ survey_fk          │ surveyTarget_fk
 *  occurrence (~30,000) ──┘                   ▼                    │
 *   sighting/absence              survey-survey-target ────────────┘
 *   records                       (0-3 per survey; ~1/3 get none)
 *          │
 *          │ organism_fk (25%)
 *          ▼
 *     organism (4,000)
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

      // Standardized target vocabulary — deliberately a small, reused pool. Real Humboldt
      // datasets draw survey targets from a handful of standard protocol definitions, not one
      // bespoke target per survey.
      .resource("survey-target")
      .allFields()
      .rows(40)
      .field("surveyTargetDescription", Gen.sample(
        "all vascular plants > 1m height",
        "breeding birds within 100m radius",
        "all bat species, mist-net and acoustic",
        "epigeic ants",
        "understory vascular flora"))
      .field("isSurveyTargetFullyReported", Gen.weighted(true, 0.65, false, 0.35))

      // Junction: which target(s) each survey was scoped to. Dist.uniform(0, 3) means some
      // surveys get no rows here at all (HumboldtExtensionBuilder's single-row-per-survey
      // path) and the rest get 1-3 (its fan-out path, joined via survey-survey-target ->
      // survey-target) — both branches now get real coverage from this recipe.
      .resource("survey-survey-target")
      .allFields()
      .per("survey", Dist.uniform(0, 3))
      // surveyTarget_fk left to the schema-fk auto default: a real lookup into the
      // survey-target pool, with replacement — several surveys legitimately share the same
      // standardized target definition.

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
