package org.studentdiscounts.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.studentdiscounts.catalog.integration.SubmissionPayloadHash;
import org.studentdiscounts.catalog.lifecycle.CatalogCategories;
import org.studentdiscounts.catalog.model.Category;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "catalog.submission.enabled=true",
      "catalog.workflow-delivery.enabled=true",
      "catalog.search-delivery.enabled=true",
      "catalog.expiration.enabled=false",
      "catalog.bootstrap.enabled=false"
    })
class CatalogWorkflowTest {
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(
              DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
          .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
  static final String SCOPE = "catalog-runtime-" + UUID.randomUUID();
  static int schemaId;

  @DynamicPropertySource
  static void dependencies(DynamicPropertyRegistry registry) throws Exception {
    DATABASE.start();
    KAFKA.start();
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("submission.events.v1", 1, (short) 1),
                  new NewTopic("catalog.publication.metadata.v1", 1, (short) 1),
                  new NewTopic("submission.workflow.v1", 1, (short) 1),
                  new NewTopic("catalog.search.events", 1, (short) 1)))
          .all()
          .get(15, TimeUnit.SECONDS);
    }
    try (var input =
        CatalogWorkflowTest.class.getResourceAsStream("/contracts/catalog-changed.schema.json")) {
      schemaId =
          MockSchemaRegistry.getClientForScope(SCOPE)
              .register(
                  "catalog.search.events-value",
                  new JsonSchema(new String(input.readAllBytes(), StandardCharsets.UTF_8)),
                  true);
    }
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.kafka.producer.properties.schema.registry.url", () -> "mock://" + SCOPE);
  }

  @AfterAll
  static void stop() {
    KAFKA.stop();
    DATABASE.stop();
  }

  @Autowired ObjectMapper json;
  @Autowired CatalogCategories categories;
  @Autowired JdbcTemplate jdbc;

  @Test
  void runningListenersQuarantineInvalidRecordWaitForMetadataAndDeliverBothContracts()
      throws Exception {
    categories.save(new Category("cafe", "Кафе", 1, true));
    UUID submission = UUID.randomUUID(), version = UUID.randomUUID();
    ObjectNode payload =
        (ObjectNode)
            json.readTree(
                """
                {"place":{"name":"Кафе","city":"Москва","addressLine":"ул. Ленина, 1","location":{"lat":55.7,"lon":37.6},"categoryCode":"cafe"},"offers":[{"conditions":"Скидка студентам","discountType":"PERCENT","discountValue":15,"source":{"type":"WEBSITE","url":"https://example.com"},"schedule":[]}],"attachments":[]}
                """);
    var snapshot = envelope(submission, version, "SubmissionSubmitted").put("aggregateSequence", 1);
    var data = (ObjectNode) snapshot.path("data");
    data.set("payload", payload);
    data.put("payloadSchemaVersion", 1)
        .put("contentSha256", SubmissionPayloadHash.hash(payload))
        .put("authorId", UUID.randomUUID().toString())
        .put("rowVersion", 1)
        .putNull("baseOfferRowVersion");
    var approval =
        envelope(submission, version, "SubmissionStatusChanged").put("aggregateSequence", 2);
    ((ObjectNode) approval.path("data"))
        .put("fromStatus", "IN_REVIEW")
        .put("toStatus", "APPROVED")
        .put("rowVersion", 4);
    var metadata = envelope(submission, version, "SubmissionPublicationMetadata");
    ((ObjectNode) metadata.path("data"))
        .put("type", "NEW_PLACE")
        .put("resolvedTimezone", "Europe/Moscow")
        .putNull("basePlaceRowVersion");
    var props = new HashMap<String, Object>();
    props.put("bootstrap.servers", KAFKA.getBootstrapServers());
    props.put("key.serializer", StringSerializer.class);
    props.put("value.serializer", StringSerializer.class);
    props.put("acks", "all");
    try (var producer = new KafkaProducer<String, String>(props)) {
      producer
          .send(
              new ProducerRecord<>(
                  "submission.events.v1", submission.toString(), snapshot.toString()))
          .get(10, TimeUnit.SECONDS);
      producer
          .send(
              new ProducerRecord<>(
                  "submission.events.v1", submission.toString(), approval.toString()))
          .get(10, TimeUnit.SECONDS);
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() ->
              assertThat(
                      jdbc.queryForObject(
                          "SELECT count(*) FROM catalog_submission_state WHERE status='APPROVED'",
                          Integer.class))
                  .isEqualTo(1));
      assertThat(jdbc.queryForObject("SELECT count(*) FROM offers", Integer.class)).isZero();
      producer
          .send(
              new ProducerRecord<>(
                  "catalog.publication.metadata.v1", submission.toString(), "invalid JSON"))
          .get(10, TimeUnit.SECONDS);
      producer
          .send(
              new ProducerRecord<>(
                  "catalog.publication.metadata.v1", submission.toString(), metadata.toString()))
          .get(10, TimeUnit.SECONDS);
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() ->
              assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_outbox", Integer.class))
                  .isEqualTo(2));
      try (var serializer = new org.studentdiscounts.catalog.integration.CatalogEventSerializer()) {
        serializer.configure(
            Map.of(
                "schema.registry.url",
                "mock://" + SCOPE,
                "auto.register.schemas",
                false,
                "json.fail.invalid.schema",
                true,
                "normalize.schemas",
                true),
            false);
        serializer.serialize(
            "catalog.search.events",
            jdbc.queryForObject(
                "SELECT payload::text FROM catalog_outbox WHERE topic='catalog.search.events'",
                String.class));
      }
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(() -> {
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM catalog_outbox WHERE published_at IS NOT NULL",
                        Integer.class))
                .isEqualTo(2);
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM catalog_inbox_quarantine", Integer.class))
                .isEqualTo(1);
          });
      producer
          .send(
              new ProducerRecord<>(
                  "submission.events.v1", submission.toString(), approval.toString()))
          .get(10, TimeUnit.SECONDS);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM offers", Integer.class)).isEqualTo(1);
    }
    var consumerProps = new HashMap<String, Object>();
    consumerProps.put("bootstrap.servers", KAFKA.getBootstrapServers());
    consumerProps.put("group.id", UUID.randomUUID().toString());
    consumerProps.put("auto.offset.reset", "earliest");
    consumerProps.put("enable.auto.commit", false);
    consumerProps.put("key.deserializer", StringDeserializer.class);
    consumerProps.put("value.deserializer", ByteArrayDeserializer.class);
    try (var consumer = new KafkaConsumer<String, byte[]>(consumerProps)) {
      consumer.subscribe(List.of("submission.workflow.v1", "catalog.search.events"));
      var received =
          new HashMap<String, org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]>>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (received.size() < 2 && System.nanoTime() < deadline)
        consumer
            .poll(Duration.ofMillis(250))
            .forEach(record -> received.put(record.topic(), record));
      assertThat(received).hasSize(2);
      var workflow = received.get("submission.workflow.v1");
      assertThat(workflow.key()).isEqualTo(submission.toString());
      assertThat(json.readTree(workflow.value()).path("eventType").asText())
          .isEqualTo("OfferPublished");
      var search = received.get("catalog.search.events");
      assertThat(search.value()[0]).isZero();
      assertThat(ByteBuffer.wrap(search.value(), 1, 4).getInt()).isEqualTo(schemaId);
      var searchBody =
          json.readTree(
              new String(search.value(), 5, search.value().length - 5, StandardCharsets.UTF_8));
      assertThat(searchBody.path("eventType").asText()).isEqualTo("CatalogChanged");
      assertThat(searchBody.path("placeId").asText()).isEqualTo(search.key());
    }
  }

  private ObjectNode envelope(UUID submission, UUID version, String type) {
    var event =
        json.createObjectNode()
            .put("eventId", UUID.randomUUID().toString())
            .put("eventType", type)
            .put("producer", "submission")
            .put("schemaVersion", 1)
            .put("occurredAt", "2026-11-19T12:00:00Z")
            .put("correlationId", "runtime-test");
    event
        .putObject("data")
        .put("submissionId", submission.toString())
        .put("versionId", version.toString())
        .put("versionNo", 1);
    return event;
  }
}
