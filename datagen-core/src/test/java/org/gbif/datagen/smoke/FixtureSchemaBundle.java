package org.gbif.datagen.smoke;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.gbif.datagen.json.JsonSupport;
import org.gbif.datagen.schema.SchemaBundle;
import org.gbif.datagen.schema.TableSchema;
import org.gbif.datagen.spec.Blueprint;

/**
 * A tiny fictitious schema used to exercise core engine behavior without depending on the real
 * DwC-DP schemas jar. Covers: a required unique PK, a self-referencing strong FK ({@code
 * widget.parent_fk}), a weak FK ({@code gizmo.widgetIDWeak -> widget.widgetID}, mirroring the
 * real {@code material.derivedFromOccurrenceID} shape), a term shared across two different
 * tables ({@code widget.widgetID} and {@code gadget.widgetIDRef}, both carrying
 * {@code WIDGET_ID_TERM}, for proving term-key portability), an enum, and a numeric range.
 */
public final class FixtureSchemaBundle implements SchemaBundle {

  private final Map<String, TableSchema> schemas = new LinkedHashMap<>();

  public static final String WIDGET_ID_TERM = "http://example.org/terms/widgetID";

  public static Blueprint.ResourceBuilder resourceWithAllFields(Blueprint bp, String name, int rows) {
    return bp.resource(name).allFields().rows(rows);
  }

  public FixtureSchemaBundle() {
    schemas.put("widget", TableSchema.fromJson("widget", JsonSupport.readObject("""
        {
          "fields": [
            {"name": "widget_pk", "type": "string", "title": "Widget PK",
             "constraints": {"required": true, "unique": true}},
            {"name": "widgetID", "type": "string", "title": "Widget ID",
             "dcterms:isVersionOf": "%s"},
            {"name": "parent_fk", "type": "string", "title": "Parent Widget"},
            {"name": "status", "type": "string", "title": "Status",
             "constraints": {"required": true, "enum": ["active", "retired"]}},
            {"name": "weight", "type": "number", "title": "Weight",
             "constraints": {"minimum": 0, "maximum": 100}},
            {"name": "notes", "type": "string", "title": "Notes"}
          ],
          "primaryKey": "widget_pk",
          "foreignKeys": [
            {"fields": "parent_fk", "predicate": "child of",
             "reference": {"resource": "", "fields": "widget_pk"}}
          ]
        }
        """.formatted(WIDGET_ID_TERM))));

    schemas.put("gadget", TableSchema.fromJson("gadget", JsonSupport.readObject("""
        {
          "fields": [
            {"name": "gadget_pk", "type": "string", "title": "Gadget PK",
             "constraints": {"required": true, "unique": true}},
            {"name": "widget_fk", "type": "string", "title": "Widget"},
            {"name": "widgetIDRef", "type": "string", "title": "Widget ID (implicit)",
             "dcterms:isVersionOf": "%s"},
            {"name": "label", "type": "string", "title": "Label"}
          ],
          "primaryKey": "gadget_pk",
          "foreignKeys": [
            {"fields": "widget_fk", "predicate": "part of",
             "reference": {"resource": "widget", "fields": "widget_pk"}}
          ]
        }
        """.formatted(WIDGET_ID_TERM))));

    schemas.put("gizmo", TableSchema.fromJson("gizmo", JsonSupport.readObject("""
        {
          "fields": [
            {"name": "gizmo_pk", "type": "string", "title": "Gizmo PK",
             "constraints": {"required": true, "unique": true}},
            {"name": "widgetIDWeak", "type": "string", "title": "Widget ID (weak)"},
            {"name": "label", "type": "string", "title": "Label"}
          ],
          "primaryKey": "gizmo_pk",
          "weakForeignKeys": [
            {"fields": "widgetIDWeak", "predicate": "derived from",
             "reference": {"resource": "widget", "fields": "widgetID"}}
          ]
        }
        """)));

    schemas.put("widget-tag", TableSchema.fromJson("widget-tag", JsonSupport.readObject("""
    {
      "fields": [
        {"name": "tag_pk", "type": "string", "title": "Tag PK",
         "constraints": {"required": true, "unique": true}},
        {"name": "widgetIDCandidate", "type": "string", "title": "Widget ID (weak, no target)"},
        {"name": "label", "type": "string", "title": "Label"}
      ],
      "primaryKey": "tag_pk",
      "weakForeignKeys": [
        {"fields": "widgetIDCandidate"}
      ]
    }
    """)));

    schemas.put("badge", TableSchema.fromJson("badge", JsonSupport.readObject("""
    {
      "fields": [
        {"name": "label", "type": "string", "title": "Label"},
        {"name": "badge_pk", "type": "string", "title": "Badge PK",
         "constraints": {"required": true, "unique": true}}
      ],
      "primaryKey": "badge_pk"
    }
    """)));

    schemas.put("voucher", TableSchema.fromJson("voucher", JsonSupport.readObject("""
    {
      "fields": [
        {"name": "voucher_pk", "type": "string", "title": "Voucher PK",
         "constraints": {"required": true, "unique": true}},
        {"name": "code", "type": "string", "title": "Code"},
        {"name": "label", "type": "string", "title": "Label"}
      ],
      "primaryKey": "voucher_pk"
    }
    """)));

    schemas.put("loop", TableSchema.fromJson("loop", JsonSupport.readObject("""
    {
      "fields": [
        {"name": "loop_pk", "type": "string", "title": "Loop PK",
         "constraints": {"required": true, "unique": true}},
        {"name": "a", "type": "string", "title": "A"},
        {"name": "b", "type": "string", "title": "B"}
      ],
      "primaryKey": "loop_pk"
    }
    """)));
  }

  @Override
  public Set<String> tableNames() {
    return schemas.keySet();
  }

  @Override
  public Optional<TableSchema> schema(String tableName) {
    return Optional.ofNullable(schemas.get(tableName));
  }

  @Override
  public Optional<String> profileUrl() {
    return Optional.of("http://example.org/test-profile.json");
  }

  @Override
  public String coordinate() {
    return "test:1.0";
  }
}
