package org.studentdiscounts.catalog.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

public class CatalogEventSerializer implements Serializer<String> {
  private final ObjectMapper json = new ObjectMapper();
  private KafkaJsonSchemaSerializer<JsonNode> search;
  private JsonNode schema;

  @Override
  public void configure(Map<String, ?> configs, boolean isKey) {
    if (isKey) throw new IllegalArgumentException("catalog serializer is value-only");
    Object registry = configs.get("schema.registry.url");
    if (registry != null && !registry.toString().isBlank()) {
      search = new KafkaJsonSchemaSerializer<>();
      search.configure(configs, false);
      try (var input =
          CatalogEventSerializer.class.getResourceAsStream(
              "/contracts/catalog-changed.schema.json")) {
        schema = json.readTree(input);
      } catch (Exception failure) {
        throw new SerializationException("catalog schema unavailable", failure);
      }
    }
  }

  @Override
  public byte[] serialize(String topic, String payload) {
    if (payload == null) return null;
    if (topic.equals("submission.workflow.v1")) return payload.getBytes(StandardCharsets.UTF_8);
    if (!topic.equals("catalog.search.events") || search == null)
      throw new SerializationException("catalog topic has no configured serializer");
    try {
      var envelope = json.createObjectNode();
      envelope.set("schema", schema);
      var body = json.readTree(payload);
      SearchSnapshotValidation.validate(body);
      envelope.set("payload", body);
      return search.serialize(topic, envelope);
    } catch (Exception failure) {
      throw new SerializationException("catalog search serialization failed", failure);
    }
  }

  @Override
  public void close() {
    if (search != null) search.close();
  }
}
