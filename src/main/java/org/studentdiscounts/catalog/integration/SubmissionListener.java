package org.studentdiscounts.catalog.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalog.submission.enabled", havingValue = "true")
public class SubmissionListener {
  private final SubmissionInbox inbox;
  private final InboxQuarantine quarantine;

  public SubmissionListener(SubmissionInbox inbox, InboxQuarantine quarantine) {
    this.inbox = inbox;
    this.quarantine = quarantine;
  }

  @KafkaListener(
      topics = {SubmissionInbox.EVENTS, SubmissionInbox.METADATA},
      containerFactory = "catalogSubmissionListenerFactory",
      groupId = "${catalog.submission.group-id:catalog-publication-v1}")
  public void receive(ConsumerRecord<String, String> record) {
    try {
      inbox.accept(record.topic(), record.key(), record.value());
    } catch (IllegalArgumentException
        | tools.jackson.core.JacksonException
        | org.springframework.dao.DuplicateKeyException invalid) {
      quarantine.save(record, invalid);
    }
  }
}
