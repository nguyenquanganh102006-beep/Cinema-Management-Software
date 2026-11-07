package com.cinema.cms.service;

import com.cinema.cms.entity.UserRole;
import org.springframework.stereotype.Service;

@Service
public class HoldOwnershipService {
  public String owner(SessionPrincipal p) {
    if (p == null || (p.role() != UserRole.STAFF && p.role() != UserRole.CUSTOMER))
      throw new ApiException(403, "ACCESS_DENIED", "Chỉ Staff hoặc Customer được giữ ghế");
    return p.role() + ":" + p.userId() + ":" + p.tokenId();
  }
}
