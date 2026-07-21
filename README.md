# dwc-dp-datagen

Generates synthetic DwC-DP data packages — CSVs plus a real `datapackage.json` — for testing
ingestion, validation, and transformation pipelines. You describe *what* to generate with a
small fluent DSL; the library handles referential integrity, cardinality, and descriptor
compliance.

## Modules

```
datagen-core/      Generation engine. Speaks Frictionless Table Schema only — no DwC-DP
                    knowledge. Reusable for any Frictionless-shaped standard.
datagen-dwcdp/      DwC-DP specifics: loads the dwc-dp-schemas jar at runtime, DwC-DP-flavored
                    generators (dates, geography, names), the real datapackage.json serializer.
datagen-recipes/    Actual datasets. Not part of the deploy chain — build/test only.
```

## Schema reference

Field names, types, constraints, and (critically) which relationships are strong
(`foreignKeys`) versus weak (`weakForeignKeys`) live in the `dwc-dp-schemas` repository, not
here. See `schemas/{version}/schema-summary.json` in that repo for a compact per-table
reference, or `schemas/{version}/table-schemas/{name}.json` for full field descriptions.

## Quick start

```java
DwcDpSchemaBundle bundle = DwcDp.bundle("1.0_DEV");

DataPackageSpec spec = Blueprint.forSchema(bundle)
    .global(Key.termOfField(bundle, "event", "decimalLatitude"),
        DwcGen.area(DwcGen.Area.DENMARK).latitude().noise(0.01))
    .global(Key.termOfField(bundle, "event", "decimalLongitude"),
        DwcGen.area(DwcGen.Area.DENMARK).longitude().noise(0.01))
    .resource("event")
        .allFields()
        .rows(150)
        .field("eventDate", DwcGen.dates().between(2015, 2025))
        .selfRef("parentEvent_fk", "event_pk").roots(0.15)
    .resource("occurrence")
        .allFields()
        .per("event", Dist.zipf(1.2).max(200))
        .field("occurrenceStatus", Gen.weighted("detected", .85, "notDetected", .15))
    .build();

GeneratedPackage pkg = spec.generate(42);
pkg.writeTo(outputDir, new DwcDpDescriptorSerializer());
```

`.build()` validates everything it can before generating a single row — unknown field names
(with a suggestion, or a full field listing if nothing's close), unknown tables, refs that
contradict the schema, cycles in the relation graph. Generation-time failures (a genuine
cross-field cycle, a `template()` reading a null value) only surface at `.generate(seed)`.

## Running the shipped recipes

```
task --list                          # see all recipes
task museum-digitization             # run one with defaults
task museum-digitization SEED=7 OUT=/tmp/fixtures BUNDLE=1.0_DEV
task all                              # run all five
```

See `Taskfile.dist.yaml`. Each recipe is a `SchemaBundle -> DataPackageSpec` builder plus a
`main(String[] args)` — `[outputDir] [seed] [bundleVersion]` — wired through `RecipeMain`.

## Loading a bundle

```java
DwcDp.bundle("1.0_DEV")   // exact version string from dwc-dp-schemas' bundles.json
```

Reads `schemas/bundles.json -> {version}/index.json -> dwc-dp-profile.json -> table-schemas/`
off the classpath at runtime. No codegen — one library build works against any published
bundle version, and picks up schema changes without a library release.

## Configuring a resource

### Field selection

| Call | Selects |
|---|---|
| `.requiredFields()` | Default. Only `constraints.required == true` fields. |
| `.allFields()` | Every field the schema defines. |
| `.fields("a", "b")` | Exactly these (required fields are added automatically). |
| `.with("c")` | Add to the current mode. |
| `.without("d")` | Remove. Fails at `.build()` if `d` is required or is a ref source/target. |

Emitted field order always follows schema order, regardless of selection order.

A ref's target field is only pulled in automatically if the ref's *source* field is itself
selected — under `.requiredFields()`, a non-required FK column (and therefore its target)
is simply absent unless you `.allFields()` or name it explicitly.

### Sizing

```java
.rows(1000)                              // absolute count
.per("event", Dist.zipf(1.2).max(200))   // fan-out from an already-declared parent resource
```

Mutually exclusive.

| `Dist` factory | Shape |
|---|---|
| `Dist.exactly(n)` | Every parent gets exactly `n`. |
| `Dist.uniform(min, max)` | Flat. |
| `Dist.gaussian(mean, stdDev, min, max)` | Normal, clamped. |
| `Dist.zipf(s).max(n)` | Heavy-tailed — most parents get few, a minority get many. |

### References

```java
.ref("event_fk", "event", "event_pk").and()
.selfRef("parentEvent_fk", "event_pk").roots(0.15)
```

Drives topological generation order (merged with schema-declared `foreignKeys` +
`weakForeignKeys`) and supplies a default lookup generator. `.declared(false)` omits the
relation from the emitted descriptor without affecting generated data — same seed, same
resource graph, byte-identical CSVs either way, only the descriptor differs.

`.selfRef(field, targetField)`: row *i* only ever points at rows `0..i-1`. `.roots(fraction)`
sets what fraction of rows get no parent.

Override the default lookup for dangling refs: `Gen.lookup("event", "event_pk").violate(0.02)`.
A lookup whose target resource generated zero rows returns `null` for every row instead of
failing — different from the controlled `.violate(p)` case above, and worth checking for if a
ref comes back entirely empty.

### Global bindings

```java
.global(Key.termOfField(bundle, "event", "decimalLatitude"), someGenerator)
.global(Key.type("number"), someGenerator)
```

`Key.termOfField(bundle, table, field)` resolves a field's `dcterms:isVersionOf` from the
loaded bundle and matches every field sharing that term across every table — not just the
one it was resolved from. `Key.type(...)` matches by Frictionless type, lower precedence.

### Resolution order

For each selected field, first match wins: **explicit `.field(name, generator)`** →
**a ref on that field** (recipe-declared or schema-declared, strong or weak, identical
treatment) → **`global(Key.term(...))`** → **`global(Key.type(...))`** → **schema default**
(derived from the field's own constraints — `enum` → sampling pool, `minimum`/`maximum` → a
bounded range, `required && unique` → a UUID, otherwise a deterministic placeholder).

### Constraint checking

On by default. Trips if a generated value violates its field's schema constraints.
`Blueprint.checkConstraints(false)` disables it, for recipes that break constraints on
purpose via `.corrupt(...)`.

## Generator catalog (`Gen.*`)

| Method | Produces |
|---|---|
| `Gen.constant(v)` | Always `v`. |
| `Gen.uuid()` | A seeded v4 UUID. |
| `Gen.sequence(start)` / `Gen.sequence()` | `start + rowIndex`. |
| `Gen.between(min, max[, decimals])` | Uniform numeric. |
| `Gen.gaussian(mean, stdDev[, min, max])` | Normal, optionally clamped. |
| `Gen.sample(List<T>)` / `Gen.sample(T...)` | Uniform draw, with replacement. |
| `Gen.sample(Generator<T>, poolSize)` | Materializes a fixed-size pool once, then samples it. |
| `Gen.sampleWithoutReplacement(List<T>)` | Each row gets a distinct value; throws on exhaustion. |
| `Gen.weighted(Map<T,Double>)` / `Gen.weighted(v1, w1, ...)` | Weighted choice. |
| `Gen.derive(row -> ...)` | Reads other already-generated fields on the same row. |
| `Gen.deriveAfter(List<String> fields, row -> ...)` | Like `derive`, waits for its dependencies. |
| `Gen.inherit(parentResource[, parentField])` | Copies a value from the `.per()` parent row. |
| `Gen.template("...{field}...")` | Composes a string from other fields on the row. |
| `Gen.lambda(ctx -> ...)` | Direct `GenContext` access. |
| `Gen.lookup(resource, field)` | Draw from an already-generated resource's column. |

`between`'s 2-arg form returns a whole number automatically when the target field's schema
type is `integer`; the `decimals` form always returns a `Double`, even at `decimals=0` — round
explicitly (e.g. `Math.round(v.doubleValue())`) if a field needs a whole-number string.
`sample(Generator<T>, poolSize)` caches its pool on that generator instance, so assigning the
result to a variable and reusing it across multiple `.field()` calls — even on different
resources — draws from the same materialized pool, which is how you keep two columns
correlated to one shared vocabulary. `weighted(...)` canonicalizes entries by key before
building its cumulative table, so the same weights generate identically for a given seed
regardless of `Map` iteration order.

Decorators wrap a `Generator<T>` and return a `Generator<T>`:

| Decorator | Effect |
|---|---|
| `.nullChance(p)` | Emits null with probability `p`. |
| `.corrupt(p, Corruption...)` | Deliberately breaks a fraction of values. Kinds: `OUT_OF_RANGE`, `UNPARSEABLE_DATE`, `WHITESPACE_PAD`, `WRONG_TYPE`, `EMPTY_STRING`, `ENCODING_ARTIFACT`, `CONTROL_CHARS`. |
| `.map(fn)` | Transforms the output. |
| `.violate(p)` (on `Gen.lookup(...)` results only) | Points a fraction of refs at a value guaranteed absent from the target pool. |

## DwC-DP-flavored generators (`DwcGen.*`)

```java
DwcGen.dates().between(2015, 2025).formats(DwcGen.DateFormat.ISO_DAY, DwcGen.DateFormat.INTERVAL)
DwcGen.area(DwcGen.Area.DENMARK).latitude().noise(0.01)    // axis is mandatory
DwcGen.area(DwcGen.Area.DENMARK).longitude().noise(0.01)
DwcGen.scientificName()
DwcGen.personName() / DwcGen.personNames(max)
DwcGen.orcid()
```

`DwcGen.DateFormat`: `ISO_DAY`, `PARTIAL_MONTH`, `PARTIAL_YEAR`, `ISO_DATETIME`, `INTERVAL`,
`VERBATIM`. `DwcGen.DateGen.yearOf/monthOf/dayOf(dateField)` derive consistent `year`/`month`/
`day` columns from an already-generated date field.

`DwcGen.area(...)` requires an explicit `.latitude()` or `.longitude()` call — there is no
axis auto-detection.

`DwcGen.personNames(max)` returns a single `|`-delimited string (1 to `max` names) — the DwC
convention for multi-agent fields like `recordedBy`, not a `List<String>`.

## Multi-pass field generation

Fields generate in schema order on the first pass. A `template()`/`deriveAfter()` referencing
a field that hasn't generated yet (e.g. a field templating off its own table's primary key)
retries automatically on a later pass. Three outcomes:

- **Not yet generated** → retried next pass.
- **Generated, value is null** → terminal, fails immediately, no retry.
- **Never resolves** (mutual dependency) → fails after one pass makes zero progress, naming
  the stuck fields.

Referenced field names that are wrong (typo, or a real field not selected on this resource)
are caught at `.build()`, before generation — with a suggestion or a full field listing.

## Writing output

```java
new PackageWriter(new DwcDpDescriptorSerializer())   // real org.gbif.dp.descriptor model
    .id("https://example.org/my-dataset")
    .created("2026-01-01T00:00:00Z")                  // fix for byte-reproducible output
    .write(pkg, outputDir);

new PackageWriter(new MapDescriptorSerializer())      // generic Frictionless map — fixtures/tests
    .write(pkg, outputDir);
```

`.writeRunMetadata(false)` skips the `datagen-run.json` sidecar (bundle coordinate, seed, row
counts, generation order).