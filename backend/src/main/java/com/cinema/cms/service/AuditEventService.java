package com.cinema.cms.service;

import com.cinema.cms.dto.AuditEventResponse;
import com.cinema.cms.dto.PageResponse;
import com.cinema.cms.entity.AuditEvent;
import com.cinema.cms.repository.AuditEventRepository;
import java.time.Clock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventService {
    private final AuditEventRepository events;
    private final Clock clock;

    public AuditEventService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void record(Long actorUserId, String action, String outcome,
                       String subjectType, Object subjectId, String details) {
        save(actorUserId, action, outcome, subjectType, subjectId, details);
    }

    /** Authentication attempts must be committed even though login itself is read-only/failing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(Long actorUserId, String action, String outcome,
                                  String subjectType, Object subjectId, String details) {
        save(actorUserId, action, outcome, subjectType, subjectId, details);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> list(int page, int size) {
        QueryParams.checkPaging(page, size, 100);
        return PageResponse.from(
                events.findAll(PageRequest.of(page, size,
                        Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")))),
                AuditEventResponse::from);
    }

    private void save(Long actorUserId, String action, String outcome,
                      String subjectType, Object subjectId, String details) {
        events.save(new AuditEvent(clock.instant(), actorUserId, action, outcome,
                subjectType, subjectId == null ? null : subjectId.toString(), details));
    }
}
