package com.cinema.cms.service;

import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class HoldExpirationSchedulerTest {
  @Test
  void oneFailedGroupDoesNotPreventLaterGroupsFromExpiring() {
    var expiration = mock(HoldExpirationService.class);
    when(expiration.candidates()).thenReturn(List.of("busy", "expired"));
    when(expiration.expire("busy")).thenThrow(new CannotAcquireLockException("test"));
    when(expiration.expire("expired")).thenReturn(true);
    new HoldExpirationScheduler(expiration).cleanup();
    verify(expiration).expire("expired");
  }
}
