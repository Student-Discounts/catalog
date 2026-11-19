package org.studentdiscounts.catalog.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "${catalog.workflow-delivery.enabled:false} or ${catalog.search-delivery.enabled:false}")
public class OutboxWorker {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);
  private final CatalogOutboxRelay relay;

  public OutboxWorker(CatalogOutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${catalog.workflow-delivery.delay-ms:500}")
  public void deliver() {
    try {
      relay.relayBatch(50);
    } catch (RuntimeException failure) {
      Throwable root = failure;
      while (root.getCause() != null && root.getCause() != root) root = root.getCause();
      LOG.warn(
          "Catalog outbox remains pending; failureType={}; rootType={}",
          failure.getClass().getSimpleName(),
          root.getClass().getSimpleName());
    }
  }
}
