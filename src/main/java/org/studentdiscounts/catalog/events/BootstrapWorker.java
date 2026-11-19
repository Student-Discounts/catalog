package org.studentdiscounts.catalog.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalog.bootstrap.enabled", havingValue = "true")
public class BootstrapWorker {
  private final CatalogEventBootstrap bootstrap;

  public BootstrapWorker(CatalogEventBootstrap bootstrap) {
    this.bootstrap = bootstrap;
  }

  @Scheduled(fixedDelayString = "${catalog.bootstrap.delay-ms:1000}")
  public void initialize() {
    bootstrap.initializeBatch(25);
  }
}
