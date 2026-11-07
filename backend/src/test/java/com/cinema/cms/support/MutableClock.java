package com.cinema.cms.support;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {
  private final AtomicReference<Instant> value =
      new AtomicReference<>(Instant.parse("2030-01-04T10:00:00Z"));

  public void set(Instant instant) {
    value.set(instant);
  }

  public void advance(long seconds) {
    value.updateAndGet(t -> t.plusSeconds(seconds));
  }

  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  public Clock withZone(ZoneId zone) {
    return this;
  }

  public Instant instant() {
    return value.get();
  }
}
