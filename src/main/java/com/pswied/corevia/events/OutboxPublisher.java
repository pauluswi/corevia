package com.pswied.corevia.events;

import com.pswied.corevia.domain.OutboxEvent;
import com.pswied.corevia.persistence.OutboxEventRepository;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {

    private static final String TOPIC = "corevia-transfers";

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, EventPublisher eventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        for (OutboxEvent event : pending) {
            try {
                eventPublisher.publish(TOPIC, event.getAggregateId(), event.getPayload());
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception ex) {
                event.markPending();
                outboxEventRepository.save(event);
            }
        }
    }
}
