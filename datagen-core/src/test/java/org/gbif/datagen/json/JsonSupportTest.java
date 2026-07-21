package org.gbif.datagen.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The hand-rolled parser this replaced always produced {@code Double} for numbers and needed a
 * workaround to avoid a spurious ".0" on whole numbers when writing a descriptor back out.
 * These confirm Jackson's default untyped binding actually behaves as claimed rather than
 * trusting the doc comment.
 */
class JsonSupportTest {

  @Test
  void wholeNumberConstraintStaysIntegerShaped() {
    Map<String, Object> parsed = JsonSupport.readObject("""
        {"constraints": {"minimum": 0, "maximum": 100}}
        """);
    @SuppressWarnings("unchecked")
    Map<String, Object> constraints = (Map<String, Object>) parsed.get("constraints");

    assertInstanceOf(Integer.class, constraints.get("minimum"),
                     "expected an Integer for a whole-number JSON literal");
    assertInstanceOf(Integer.class, constraints.get("maximum"),
                     "expected an Integer for a whole-number JSON literal");
    assertEquals(0, ((Number) constraints.get("minimum")).intValue());
    assertEquals(100, ((Number) constraints.get("maximum")).intValue());
  }

  @Test
  void decimalNumberStaysDouble() {
    Map<String, Object> parsed = JsonSupport.readObject("""
        {"noise": 0.01}
        """);
    assertInstanceOf(Double.class, parsed.get("noise"));
    assertEquals(0.01, (Double) parsed.get("noise"), 1e-9);
  }

  @Test
  void fieldOrderSurvivesParseAndReSerializeRoundTrip() {
    // Deliberately non-alphabetical — an accidental TreeMap or key-sort anywhere in the chain
    // is only caught by an order that sorting would visibly disturb.
    String original = """
        {"fields": [
          {"name": "zeta"}, {"name": "alpha"}, {"name": "middle"}
        ]}
        """;
    Map<String, Object> parsed = JsonSupport.readObject(original);
    String reSerialized = JsonSupport.writePretty(parsed);
    Map<String, Object> reparsed = JsonSupport.readObject(reSerialized);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) reparsed.get("fields");
    List<Object> names = fields.stream().map(f -> f.get("name")).toList();

    assertEquals(List.of("zeta", "alpha", "middle"), names,
                 "field order must survive a parse -> re-serialize round trip, since CSV column order"
                 + " depends on it");
  }
}
