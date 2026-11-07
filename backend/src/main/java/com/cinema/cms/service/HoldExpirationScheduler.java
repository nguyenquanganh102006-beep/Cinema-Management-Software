package com.cinema.cms.service;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldExpirationScheduler {
  private final HoldExpirationService expiration;

  public HoldExpirationScheduler(HoldExpirationService expiration) {
    this.expiration = expiration;
  }

  @Scheduled(
      fixedDelayString = "${app.holds.cleanup-delay-ms:10000}",
      initialDelayString = "${app.holds.cleanup-delay-ms:10000}")
  public void cleanup() {
    int count = 0;
    for (var token : expiration.candidates()) {
      try {
        if (expiration.expire(token)) count++;
      } catch (RuntimeException e) {
        // A failed candidate must not roll back groups already processed or stop the batch.
        LoggerFactory.getLogger(getClass())
            .warn("Hold cleanup skipped failureType={}", e.getClass().getSimpleName());
      }
    }
    if (count > 0) LoggerFactory.getLogger(getClass()).debug("Expired hold groups={}", count);
  }
}
