package org.studentdiscounts.catalog.integration;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalog.workflow-delivery.enabled", havingValue = "true")
public class WorkflowTransport implements EventTransport {
  private final KafkaTemplate<String, String> kafka;

  public WorkflowTransport(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @Override
  public Set<String> topics() {
    return Set.of("submission.workflow.v1");
  }

  @Override
  public void send(String topic, String key, String payload) {
    if (!topics().contains(topic)) throw new IllegalArgumentException("workflow.topic");
    try {
      kafka.send(topic, key, payload).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("workflow delivery interrupted", interrupted);
    } catch (Exception failure) {
      throw new IllegalStateException("workflow delivery failed", failure);
    }
  }
}
