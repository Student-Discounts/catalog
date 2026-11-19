package org.studentdiscounts.catalog.integration;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalog.search-delivery.enabled", havingValue = "true")
public class SearchTransport implements EventTransport {
  private final KafkaTemplate<String, String> kafka;

  public SearchTransport(
      KafkaTemplate<String, String> kafka,
      @Value("${spring.kafka.producer.properties.schema.registry.url:}") String registry) {
    if (registry.isBlank())
      throw new IllegalArgumentException("search delivery requires schema.registry.url");
    this.kafka = kafka;
  }

  @Override
  public Set<String> topics() {
    return Set.of("catalog.search.events");
  }

  @Override
  public void send(String topic, String key, String payload) {
    if (!topics().contains(topic)) throw new IllegalArgumentException("search.topic");
    try {
      kafka.send(topic, key, payload).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("search delivery interrupted", interrupted);
    } catch (Exception failure) {
      throw new IllegalStateException("search delivery failed", failure);
    }
  }
}
