# dwc-dp-datagen

A library for generating synthetic DwC-DP data packages — for exercising ingestion,
validation, and transformation pipelines with realistic, schema-valid (or deliberately
invalid) fixture data. Not a fixed dataset: a small DSL for describing *how* to generate one,
built from the real Frictionless Table Schemas rather than a hand-maintained model of them.

## Module layout

```
datagen-core/      Frictionless-only generation engine. No DwC-DP knowledge at all —
                    speaks Table Schema, foreign keys, constraints. Reusable for any
                    Frictionless-shaped standard (camtrap-dp, etc).
datagen-dwcdp/      DwC-DP specifics: loads the real dwc-dp-schemas jar at runtime
                    (schemas/bundles.json -> {version}/index.json -> table-schemas/*.json,
                    no codegen), plus DwC-DP-flavored generators (dates, geography, names)
                    and the real datapackage.json serializer.
datagen-recipes/    Actual datasets (see the 5 shipped recipes). Not part of the deploy
                    chain — build/test only.
```

If you're writing a recipe, you'll mostly touch `datagen-dwcdp` (for `DwcDp.bundle(...)` and
`DwcGen.*`) and `datagen-core` (for `Blueprint`, `Gen.*`, `Dist.*`, `Key`). You should never
need to touch `datagen-core`'s internals to write a recipe.

## Why schemas are loaded at runtime, not generated into code

`dwc-dp-schemas` ships only JSON — `bundles.json`, `index.json`, `dwc-dp-profile.json`,
`table-schemas/*.json` — as classpath resources, no `.java` at all. `DwcDp.bundle("1.0_DEV")`
reads all of that at startup: no directory listing anywhere (which isn't portable across
`file:`/`jar:`/shaded-jar classloaders), just `getResourceAsStream` on paths read out of
`index.json` and `bundles.json`. This means:

- **Schema version is a runtime argument**, not a build-time coupling. One library build
  generates fixtures against 0.1 and 1.0_DEV, and picks up schema changes without a
  library release.
- **Field descriptors round-trip verbatim.** `Field.raw()` holds the complete original JSON
  object; the descriptor serializer copies it back out rather than reconstructing it from a
  partial model. Custom properties (`weakPrimaryKey`, `weakForeignKeys`, `predicate`) can't be
  silently dropped, because nothing here enumerates "known" properties.

## Core concepts

### SchemaBundle

The one abstraction `datagen-core` knows about a schema source:

```java
DwcDpSchemaBundle bundle = DwcDp.bundle("1.0_DEV"); // exact version string from bundles.json
```

`bundle.requireSchema("event")` gives you a `TableSchema` — fields, primary key, weak
primary key, foreign keys, weak foreign keys. Everything downstream is built from this.

### Blueprint — the fluent entry point

```java
DataPackageSpec spec = Blueprint.forSchema(bundle)
    .global(Key.termOfField(bundle, "event", "decimalLatitude"), DwcGen.area(DENMARK).latitude().noise(0.01))
    .resource("event")
        .allFields()
        .rows(150)
        .field("eventDate", DwcGen.dates().between(2015, 2025))
        .selfRef("parentEvent_fk", "event_pk").roots(0.15)
    .resource("occurrence")
        .per("event", Dist.zipf(1.2).max(200))
        .field("occurrenceStatus", Gen.weighted("present", .85, "absent", .15))
    .build();

GeneratedPackage pkg = spec.generate(42);          // seed -> deterministic output
pkg.writeTo(outputDir, new DwcDpDescriptorSerializer());
```

`.build()` validates everything it can before generating a single row: unknown field names
(with a "did you mean" suggestion, or a full field listing if nothing's close), unknown
tables, refs that contradict the schema, cycles in the merged relation graph. Generation
failures (a genuine cross-field cycle, a `template()` reading a null value) only happen at
`.generate(seed)`, because they depend on actual data, not just the shape of the recipe.

### Resolution order — what decides a field's value

For each selected field, in this order, first match wins:

1. **Explicit `.field(name, generator)`** on the resource.
2. **A ref on that field** — recipe-declared (`.ref(...)`/`.selfRef(...)`) or schema-declared
   (`foreignKeys`/`weakForeignKeys`), strong or weak, treated identically. Gets a real
   `lookup`/self-ref/anchor-aware default automatically — **but only if the field is actually
   selected** (see the gotcha below).
3. **`global(Key.term(...), generator)`** — matches by `dcterms:isVersionOf`, so one binding
   applies across every table carrying that term. This is the version-portable layer: the
   term URI survives the 0.1 -> 1.0 field renames even where the field name doesn't.
4. **`global(Key.type(...), generator)`** — matches by Frictionless type (`string`, `number`...).
5. **Schema default** (`SchemaDefaults`) — derived from the field's own constraints: `enum` ->
   sampling pool, `minimum`/`maximum` -> a bounded range, `required && unique` -> a UUID,
   anything else -> a deterministic placeholder (`fieldName-rowIndex`, 50% null if not
   required). This is why `.resource("event").rows(100)` with zero configuration already
   produces schema-valid data — the fluent API exists to make data *interesting*, not to make
   it *valid*.

**Gotcha:** ref-forcing (step 2's "the target field gets pulled in even if not required") is
gated behind the *source* field already being selected. Under the default `REQUIRED` mode, a
non-required FK/weak-FK column is simply absent unless you `.allFields()` or name it
explicitly — and if it's absent, nothing forces its target in either, and there's no
ordering dependency on it. This is deliberate (declaring a narrow resource shouldn't silently
drag in every table the schema happens to reference), but it means "I want this FK column
populated with a real lookup" almost always means reaching for `.allFields()`.

### Field selection

| Call | Behavior |
|---|---|
| `.requiredFields()` | Default. Only `constraints.required == true` fields. |
| `.allFields()` | Every field the schema defines. |
| `.fields("a", "b")` | Exactly these (+ required fields, automatically; naming them is a no-op). |
| `.with("c")` | Add on top of the current mode. |
| `.without("d")` | Remove — fails at `.build()` if `d` is required or is a ref source/target. |

Emitted field order is always **schema order**, regardless of the order you name fields in —
the guide requires descriptor `fields` order to match CSV column order, so two recipes
selecting the same fields produce byte-identical column layout.

### Sizing a resource

```java
.rows(1000)                              // absolute count
.per("event", Dist.zipf(1.2).max(200))   // fan-out from an already-declared parent
```

Mutually exclusive. `Dist` options:

| Factory | Shape |
|---|---|
| `Dist.exactly(n)` | Every parent gets exactly `n`. |
| `Dist.uniform(min, max)` | Flat. |
| `Dist.gaussian(mean, stdDev, min, max)` | Normal, clamped. |
| `Dist.zipf(s).max(n)` | Heavy-tailed: most parents get few, a minority get many. Use this for realistic 1-to-many fan-out (e.g. occurrences per event) — `uniform` gives every event ~the same count, which under-exercises whatever's meant to handle a large join. |

### References — `.ref()` and `.selfRef()`

```java
.resource("occurrence")
    .ref("event_fk", "event", "event_pk").and()
```

Two independent axes:

- **Generation** — always happens: drives topological ordering (schema FKs + recipe refs,
  merged; a recipe ref can introduce a cycle the schema's strong graph doesn't have, caught
  at `.build()`) and supplies the default lookup generator.
- **`.declared(false)`** — whether the relation appears in the emitted descriptor. Generating
  the same dataset once declared, once not, produces byte-identical CSVs and differing
  descriptors — this is how you manufacture the "implicit reference" shape real publishers
  emit, where the data honors a relation the descriptor is silent about.

`.selfRef(field, targetField)` — row *i* may only point at rows `0..i-1`, so acyclicity is
structural, not hoped-for. `.roots(fraction)` tunes what fraction get no parent (row 0 always
does).

`.ref(...)` returns a `Lookup`-producing default; override with `.violate(p)` for a
deliberately dangling FK a fraction of the time:

```java
.field("event_fk", Gen.lookup("event", "event_pk").violate(0.02))  // 2% dangle
```

### Global bindings — `Key`

```java
.global(Key.termOfField(bundle, "event", "decimalLatitude"), DwcGen.area(DENMARK).latitude())
```

`Key.termOfField(bundle, table, field)` reads the field's `dcterms:isVersionOf` off the
loaded bundle — there's no hand-maintained term-constant list (DwC-DP has 200+ terms; a
constants class would either need codegen, reintroducing the coupling runtime-loading was
meant to avoid, or drift out of date). The returned `Key` matches every field sharing that
term across *every* table, not just the one it was resolved from — that cross-table match is
the entire point of term-keying. `Key.type("number")` matches by Frictionless type instead,
lower precedence.

## Generator catalog (`Gen.*`)

Quick reference, then each one with what it's actually for and how it behaves.

| Method | Produces |
|---|---|
| `Gen.constant(v)` | Always `v`. |
| `Gen.uuid()` | A v4 UUID, deterministic from the per-cell seed. |
| `Gen.sequence(start)` / `Gen.sequence()` | `start + rowIndex`. |
| `Gen.between(min, max[, decimals])` | Uniform numeric. |
| `Gen.gaussian(mean, stdDev[, min, max])` | Normal, optionally clamped. |
| `Gen.sample(List<T>)` / `Gen.sample(T...)` | Uniform draw, with replacement. |
| `Gen.sample(Generator<T>, poolSize)` | Materializes a pool once, then samples it. |
| `Gen.sampleWithoutReplacement(List<T>)` | Each row gets a distinct value. |
| `Gen.weighted(...)` | Weighted choice. |
| `Gen.derive(fn)` | Reads other fields already on this row. |
| `Gen.deriveAfter(fields, fn)` | Like `derive`, but waits for its dependencies. |
| `Gen.inherit(parentResource[, parentField])` | Copies a field from the `.per()` parent row. |
| `Gen.template("...{field}...")` | Composes a string from other fields on this row. |
| `Gen.lambda(ctx -> ...)` | Full escape hatch. |
| `Gen.lookup(resource, field)` | Draw from an already-generated resource's column. |
| `.nullChance(p)` / `.corrupt(p, kinds)` / `.map(fn)` | Decorators — wrap any `Generator<T>`. |

### Constants and identity

```java
.field("materialEntityType", Gen.constant("PreservedSpecimen"))
```
`Gen.constant(v)` — always the same value. The obvious choice for a field with one correct
answer for the whole recipe (a fixed institution code, a fixed basis of record for a
single-domain dataset).

```java
.field("occurrence_pk", Gen.uuid())
```
`Gen.uuid()` — a real v4 UUID (correct version/variant bits set), built from the per-cell
`Random` rather than `UUID.randomUUID()`, so it's reproducible at a given seed like everything
else here. You'll rarely call this directly — it's what `SchemaDefaults` already uses for any
`required && unique` field with no explicit generator — but it's there when you want a UUID on
a field that *isn't* required/unique, or want to be explicit.

```java
.field("catalogNumber", Gen.sequence(100_000))
```
`Gen.sequence(start)` — `start + rowIndex`: ordered, not random. Real catalog numbers,
accession numbers, and similar look like this — sequential, not UUID-shaped — so reach for
this instead of `uuid()` whenever the field represents something a real institution assigns
in order. `Gen.sequence()` with no argument starts at 1.

### Numeric ranges

```java
.field("individualCount", Gen.between(1, 50))
.field("decimalLatitude", Gen.between(-90, 90, 6))   // 6 decimal places
```
`Gen.between(min, max)` — uniform. If the field's schema type is `integer`, the result is
rounded to a whole number automatically; otherwise it's a `double`. The three-argument form
rounds to a fixed number of decimal places, which matters for coordinates and other fields
where an unrounded double looks obviously synthetic in a CSV.

```java
.field("organismQuantity", Gen.gaussian(12, 4, 1, 100))
```
`Gen.gaussian(mean, stdDev[, min, max])` — normal distribution, clamped into `[min, max]` if
given. Reach for this over `between` when a field should cluster around a typical value rather
than being flat across its whole range (body counts, measurements) — `between` makes every
value in range equally likely, which reads as obviously synthetic for anything that has a
real-world "typical" value.

### Sampling and weighting

```java
.field("institutionCode", Gen.sample(List.of("NHMD", "SNM", "ZMUC", "GBIF-TEST")))
.field("mediaType", Gen.sample("StillImage", "Sound", "MovingImage"))   // varargs form
```
`Gen.sample(List<T>)` / `Gen.sample(T...)` — uniform draw with replacement from a fixed pool.
The default for "pick one of these N things, no preference among them."

```java
.field("scientificName", Gen.sample(DwcGen.scientificName(), 500))
```
`Gen.sample(Generator<T>, poolSize)` — for when you want a *bounded, reused* set of values
rather than every row getting an independently fresh one. Materializes `poolSize` values from
the given generator once (lazily, on first use, cached), then samples that pool with
replacement for every row after. Use this when the domain has a fixed vocabulary you want
repeated across rows — e.g. "there are 500 distinct taxa in this dataset, occurrences repeat
them" — rather than every occurrence getting a unique never-repeated name, which would be
`DwcGen.scientificName()` used directly instead (independently fresh per row, no pooling).

```java
.field("catalogNumber", Gen.sampleWithoutReplacement(preAssignedNumbers))
```
`Gen.sampleWithoutReplacement(List<T>)` — each row gets a *distinct* value from the pool (a
seeded shuffle indexed by row, not a mutable "remove as drawn" — so it stays parallelizable
and deterministic). Throws immediately if the resource has more rows than the pool has
values — fails loudly on exhaustion instead of silently repeating a value, which for a field
where uniqueness actually matters (e.g. real-world assigned identifiers you're replaying) is
the behavior you want, not a bug.

```java
.field("occurrenceStatus", Gen.weighted("present", 0.85, "absent", 0.15))

Map<String, Double> weights = new LinkedHashMap<>();
weights.put("Male", 0.42);
weights.put("Female", 0.42);
weights.put("Undetermined", 0.16);
.field("sex", Gen.weighted(weights))
```
`Gen.weighted(...)` — weighted choice, two call shapes: inline varargs
(`value1, weight1, value2, weight2, ...`) for a handful of alternatives, or a `Map<T, Double>`
for a larger or programmatically-built pool. Weights don't need to sum to 1 — they're
normalized. **Map entries are canonicalized by sorting on the key's string form before the
cumulative-weight array is built** — `Map.of()` randomizes its iteration order per JVM run, so
without this the *same seed* could silently produce *different data* on different runs. This
also means two maps with identical content but different insertion order generate identically
— determinism keyed on content, not construction order.

### Correlation — reading other fields on the same row

```java
.field("countryCode", Gen.derive(row -> switch (row.getString("country")) {
    case "Denmark" -> "DK";
    case "Kenya" -> "KE";
    default -> null;
}))
```
`Gen.derive(row -> ...)` — reads already-generated fields off the current row via plain
`Row.get`/`Row.getString`. This is how correlated fields stay correlated instead of being
drawn independently (`countryCode` must agree with `country`; `year`/`month`/`day` must agree
with `eventDate`) — without it you'd generate internally incoherent rows and then be unable to
tell whether a pipeline bug is real or your fixture is just nonsense. **Requires its
dependency to have already generated on this row** — since fields run in schema order, this
only works reliably for fields that are earlier in schema order than the one you're deriving.
If that's not guaranteed (e.g. deriving from a field whose position you don't control, or your
own table's primary key), use `deriveAfter` instead.

```java
.field("label", Gen.deriveAfter(List.of("badge_pk"), row -> "BADGE-" + row.getString("badge_pk")))
```
`Gen.deriveAfter(requiredFields, fn)` — like `derive`, but declares up front which fields it
needs. If any haven't generated yet on this row, the engine automatically retries this field on
a later pass rather than failing (see **Multi-pass generation**, below) — so it's safe to use
regardless of where the dependency sits in schema field order, including a field's own table's
primary key. The required-fields list is also checked at `.build()`: a typo or a field that
isn't selected on this resource fails immediately with a suggestion, rather than surfacing at
generation time as an ambiguous retry failure.

```java
.field("decimalLatitude", Gen.inherit("event"))            // same field name on the parent
.field("countryCode", Gen.inherit("event", "countryCode")) // explicit parent field name
```
`Gen.inherit(parentResource[, parentField])` — copies a value straight from the row's
anchoring `.per()` parent. Use this when a child resource should share a value with its
parent exactly (an occurrence's coordinates matching its event's, rather than being
independently regenerated and only coincidentally close) — only meaningful on a resource sized
with `.per(parentResource, dist)`, since that's what supplies the parent row.

```java
.field("identifier", Gen.template("https://herbarium.example.org/specimen/{materialIdentifier_pk}"))
.field("organismName", Gen.template("BAND-{organismID}"))
```
`Gen.template("...{field}...")` — string composition from other fields on the same row, one
or more `{fieldName}` placeholders. This is the most common way to build realistic-looking
identifiers/URLs/labels out of a row's own generated data. Same multi-pass retry behavior as
`deriveAfter` (a placeholder referencing a field that hasn't generated yet gets retried
automatically), and the same build-time validation — an unknown or unselected placeholder
field fails at `.build()` with a suggestion or a full field listing, not a cryptic runtime
error. **One case template can't help with:** if the referenced field's generator runs and
returns null, that's terminal — template fails immediately with no retry, since the value
won't change no matter how many passes run. Either the referenced field shouldn't have
`.nullChance()`, or don't reference it in the template.

```java
.field("eventRemarks", Gen.lambda(ctx -> {
    if (ctx.random().nextBoolean()) {
        return "Recorded during " + ctx.get("eventType") + " on " + ctx.rowIndex();
    }
    return null;
}))
```
`Gen.lambda(ctx -> ...)` — the full escape hatch, direct access to `GenContext` (seed,
`random()`, `resource()`/`field()`/`rowIndex()`, `get(field)`, `row()`, `pool(resource,
field)`, `parentRow(...)`). Reach for this only when the other generators genuinely don't fit
— most correlation needs are `derive`/`deriveAfter`, most string composition is `template`,
most cross-resource reads are `lookup`. If you find yourself writing the same `lambda` shape
across multiple recipes, that's usually a sign it wants to be a named generator in `Gen` or
`DwcGen` instead.

### References across resources

```java
Gen.lookup("event", "event_pk")
Gen.lookup("event", "event_pk").violate(0.02)   // 2% of rows dangle deliberately
```
`Gen.lookup(resource, field)` — draws a value from an already-generated resource's column,
with replacement (so the same target can legitimately be pointed at by many rows — a photo
illustrating several sightings, a banded bird recaptured several times). This is what `.ref()`
and schema-declared foreign keys resolve to automatically by default; you'll write it
explicitly mainly to override the *null behavior* around a ref (an auto-wired schema FK has no
null-modeling — see the resolution-order gotcha above — so "not every occurrence has an
organism" needs `Gen.lookup("organism", "organism_pk").nullChance(0.7)` spelled out). `.violate(p)`
does the opposite of `.nullChance` — instead of sometimes omitting the reference, it sometimes
points it at a value guaranteed *not* to be in the target pool, for exercising a pipeline's
handling of genuinely dangling foreign keys rather than merely missing ones.

### Decorators — wrap a `Generator<T>`, return a `Generator<T>`

```java
Gen.between(0, 100).nullChance(0.3)
```
`.nullChance(p)` — emits null with probability `p` instead of the wrapped generator's real
value, simulating missing data on an otherwise-populated field. Draws from a stream
independent of the wrapped generator's own (keyed off the same per-cell seed with a different
salt), so adding or tuning a `.nullChance()` doesn't reshuffle the field's non-null values —
important for keeping golden-file diffs attributable to the actual change you made.

```java
Gen.between(0, 100).corrupt(0.05, Corruption.OUT_OF_RANGE)
Gen.constant("2020-01-01").corrupt(0.1, Corruption.UNPARSEABLE_DATE, Corruption.WHITESPACE_PAD)
```
`.corrupt(p, Corruption...)` — deliberately breaks a fraction of values. An
ingestion/validation pipeline has no real test surface without invalid input, so this exists
to manufacture it on purpose rather than leaving "what does a broken record look like" to
chance. Kinds: `OUT_OF_RANGE` (pushes a number past its schema `minimum`/`maximum`),
`UNPARSEABLE_DATE`, `WHITESPACE_PAD` (leading/trailing whitespace a naive parser forgets to
trim), `WRONG_TYPE` (non-numeric text in a numeric column), `EMPTY_STRING` (distinct from
null), `ENCODING_ARTIFACT` (mojibake, as if UTF-8 were decoded as Latin-1), `CONTROL_CHARS`
(embedded control characters that break naive delimited parsing). Multiple kinds passed
together pick one at random per corrupted cell. This is per-cell, not per-record — a realistic
"one entire row is garbage" case is a different mechanism and isn't built here. Constraint
checking (on by default) will trip on an `OUT_OF_RANGE` corruption unless you either expect
that (confirming the corruption actually produced something invalid) or call
`Blueprint.checkConstraints(false)`.

```java
Gen.sample(List.of("male", "female")).map(String::toUpperCase)
```
`.map(fn)` — transforms the generator's output, preserving its underlying random stream
(a null passes through unchanged rather than being fed to `fn`). Use this to reshape an
existing generator's output (case, formatting, wrapping in a prefix) rather than writing a new
generator from scratch for a trivial transformation.

## DwC-DP-flavored generators (`DwcGen.*`, in `datagen-dwcdp`)

```java
DwcGen.dates().between(2015, 2025).formats(DwcGen.DateFormat.ISO_DAY, DwcGen.DateFormat.INTERVAL)
DwcGen.area(DwcGen.Area.DENMARK).latitude().noise(0.01)
DwcGen.area(DwcGen.Area.DENMARK).longitude().noise(0.01)
DwcGen.scientificName()                           // binomial, not a real taxonomy
DwcGen.personName() / DwcGen.personNames(max)     // pipe-concatenated agent lists
DwcGen.orcid()
```

`DwcGen.DateFormat` covers the real shapes DwC dates take in the wild —
`ISO_DAY`/`PARTIAL_MONTH`/`PARTIAL_YEAR`/`ISO_DATETIME`/`INTERVAL`/`VERBATIM` — deliberately
messy on purpose, since that messiness is exactly what a validator needs exercised.
`DateGen.yearOf/monthOf/dayOf(dateField)` derive consistent `year`/`month`/`day` columns from
an already-generated date field.

## Multi-pass generation — what `template()`/`deriveAfter()` actually guarantee

Fields generate in **schema order** on the first pass. If a `template()`/`deriveAfter()`
needs a field that hasn't run yet (commonly: a field templating off its own table's primary
key, which can appear anywhere in schema order), the engine retries it on a later pass rather
than failing immediately. This terminates: every pass either makes progress or the engine
stops and reports exactly which fields are stuck waiting on what.

Three distinct outcomes, and they're not interchangeable:

- **Not yet generated** → retried on the next pass.
- **Generated, value is null** → **terminal**, fails immediately, no retry — the value won't
  change no matter how many passes run. If a `template()`/`deriveAfter()` reference resolves
  to null, either drop `.nullChance()` from the referenced field or don't reference it.
- **Never resolves** (a genuine mutual dependency between two fields) → fails after one full
  pass makes zero progress, with the specific stuck fields named — not an infinite loop.

Referenced field names that are simply wrong (typo, or a real field that isn't selected on
this resource) are caught at `.build()`, before any of this runs — with a suggestion, or a
full field listing if nothing's close enough to guess.

## Writing output

```java
GeneratedPackage pkg = spec.generate(seed);

pkg.writeTo(outputDir, new DwcDpDescriptorSerializer());  // real org.gbif.dp.descriptor model
pkg.writeTo(outputDir, new MapDescriptorSerializer());    // generic Frictionless map — fine for
                                                           // tests/fixtures with no real model
```

`PackageWriter(DescriptorSerializer)` — the serializer is a required constructor argument,
not a default, on purpose: forgetting it wouldn't fail or look wrong, it'd just silently emit
the generic descriptor instead of one built on the real model. `.id(...)`/`.created(...)` fix
a reproducible id/timestamp (otherwise `created` is a live timestamp and output isn't
byte-identical run to run even at the same seed); `.writeRunMetadata(false)` skips the
`datagen-run.json` sidecar (bundle coordinate, seed, row counts, generation order — useful
for attributing a golden-file diff to a schema change vs. a recipe change vs. a library bug).

## Recipes

Five shipped in `datagen-recipes` (`BirdBandingRecipe`, `HerbariumSpecimenRecipe`,
`CitizenSciencePhotoRecipe`, `HumboldtSurveyRecipe`, `MuseumDigitizationRecipe`), runnable via
`task <recipe-name>` — see `Taskfile.dist.yaml`. Each is a `SchemaBundle -> DataPackageSpec`
builder plus a `main` wired through the shared `RecipeMain` CLI helper
(`[outputDir] [seed] [bundleVersion]`). Use them as the reference shape for a new recipe —
same `Blueprint` chain, same `RecipeMain.run(args, name, YourRecipe::build)` boilerplate.