package com.pswied.corevia.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.pswied.corevia.domain.OutboxEvent;
import com.pswied.corevia.persistence.OutboxEventRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private OutboxPublisher outboxPublisher;

    @Test
    void publishPendingEventsPublishesPendingOutboxRecords() {
        OutboxEvent event = new OutboxEvent("transaction", "TXN-001", "TransferSucceeded", "{\"eventType\":\"TransferSucceeded\"}");
        org.mockito.Mockito.when(outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(event));

        outboxPublisher.publishPendingEvents();

        verify(eventPublisher).publish("corevia-transfers", "TXN-001", "{\"eventType\":\"TransferSucceeded\"}");
        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
    }
}
