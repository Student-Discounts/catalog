package org.studentdiscounts.catalog.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalog.submission.enabled", havingValue = "true")
public class PublicationWorker {
  private final ApprovedPublicationWorker worker;

  public PublicationWorker(ApprovedPublicationWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${catalog.submission.delay-ms:500}")
  public void publish() {
    worker.publishBatch(25);
  }
}
