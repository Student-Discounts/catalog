package org.studentdiscounts.catalog.integration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogEventSerializerTest {
  @Test
  void searchUsesRegistrySchemaIdAndWorkflowRemainsPlainJson() throws Exception {
    var scope = "catalog-" + UUID.randomUUID();
    var json = new ObjectMapper();
    try (var serializer = new CatalogEventSerializer();
        var schemaInput =
            getClass().getResourceAsStream("/contracts/catalog-changed.schema.json")) {
      var schema = new JsonSchema(json.readTree(schemaInput));
      int id =
          MockSchemaRegistry.getClientForScope(scope)
              .register("catalog.search.events-value", schema);
      serializer.configure(
          Map.of(
              "schema.registry.url",
              "mock://" + scope,
              "auto.register.schemas",
              false,
              "json.fail.invalid.schema",
              true),
          false);
      String body =
          """
          {"eventId":"14a0b145-663d-4ec4-b02d-28028fba54b8","eventType":"CatalogChanged","occurredAt":"2026-11-19T12:00:00Z","placeId":"e74cdfc2-3a53-43b1-aef1-d027a95d51f3","data":{"place":{"revision":0,"placeName":"Кафе","city":"Москва","addressLine":"ул. Ленина, 1","location":{"latitude":55.7,"longitude":37.6},"categoryCode":"cafe","categoryName":"Кафе","timezone":"Europe/Moscow"}}}
          """;
      byte[] encoded = serializer.serialize("catalog.search.events", body);
      assertThat(encoded[0]).isZero();
      assertThat(ByteBuffer.wrap(encoded, 1, 4).getInt()).isEqualTo(id);
      assertThat(json.readTree(new String(encoded, 5, encoded.length - 5, StandardCharsets.UTF_8)))
          .isEqualTo(json.readTree(body));
      assertThat(
              new String(
                  serializer.serialize("submission.workflow.v1", "{\"ok\":true}"),
                  StandardCharsets.UTF_8))
          .isEqualTo("{\"ok\":true}");
      assertThatThrownBy(
              () -> serializer.serialize("catalog.search.events", "{\"eventType\":\"wrong\"}"))
          .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class);
    } finally {
      MockSchemaRegistry.dropScope(scope);
    }
  }

  @Test
  void searchWithoutRegistryCannotSilentlySendPlainJson() {
    try (var serializer = new CatalogEventSerializer()) {
      serializer.configure(Map.of(), false);
      assertThatThrownBy(() -> serializer.serialize("catalog.search.events", "{}"))
          .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class);
      assertThatThrownBy(() -> serializer.serialize("unconfigured.topic", "{}"))
          .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class);
    }
  }

  @Test
  void semanticScheduleValidationRejectsMoreThan28ExpandedIntervals() throws Exception {
    var json = new ObjectMapper();
    var message =
        json.readTree(
            "{\"data\":{\"offers\":[{\"offerId\":\"36b91b96-bcbc-4c53-8a33-7773ff60888c\",\"validFrom\":null,\"validUntil\":null,\"schedule\":[]}]}}");
    var schedule =
        (com.fasterxml.jackson.databind.node.ArrayNode)
            message.path("data").path("offers").get(0).path("schedule");
    var group = schedule.addObject();
    group.set("daysOfWeek", json.readTree("[1,2,3,4,5,6,7]"));
    var intervals = group.putArray("intervals");
    for (int hour = 0; hour < 5; hour++)
      intervals
          .addObject()
          .put("timeFrom", String.format("%02d:00", hour))
          .put("timeUntil", String.format("%02d:00", hour + 1));
    assertThatThrownBy(() -> SearchSnapshotValidation.validate(message))
        .hasMessage("expanded schedule limit");
    intervals.remove(4);
    assertThatCode(() -> SearchSnapshotValidation.validate(message)).doesNotThrowAnyException();
  }

  @Test
  void semanticValidationRejectsOffsetTimezoneAndDuplicateUuidValues() throws Exception {
    var json = new ObjectMapper();
    var offset = json.readTree("{\"data\":{\"place\":{\"timezone\":\"+03:00\"}}}");
    assertThatThrownBy(() -> SearchSnapshotValidation.validate(offset)).hasMessage("IANA timezone");
    var duplicate =
        json.readTree(
            "{\"data\":{\"offers\":[{\"offerId\":\"36b91b96-bcbc-4c53-8a33-7773ff60888c\",\"validFrom\":null,\"validUntil\":null},{\"offerId\":\"36B91B96-BCBC-4C53-8A33-7773FF60888C\",\"validFrom\":null,\"validUntil\":null}]}}");
    assertThatThrownBy(() -> SearchSnapshotValidation.validate(duplicate))
        .hasMessage("duplicate offer");
  }
}
