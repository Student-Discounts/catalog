package org.studentdiscounts.catalog.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalog.expiration.enabled", havingValue = "true")
public class ExpirationWorker {
  private final OfferExpiration expiration;

  public ExpirationWorker(OfferExpiration expiration) {
    this.expiration = expiration;
  }

  @Scheduled(fixedDelayString = "${catalog.expiration.delay-ms:60000}")
  public void expire() {
    expiration.expireBatch(50);
  }
}
