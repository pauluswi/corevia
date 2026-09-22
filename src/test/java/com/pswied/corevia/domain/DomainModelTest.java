package com.pswied.corevia.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DomainModelTest {

    @Test
    void transactionStartsReceivedAndCanAdvanceThroughLifecycle() {
        Transaction transaction = new Transaction(
            "TXN-20260922-000001",
            "1000012345",
            "2000098765",
            new BigDecimal("1500000.00"),
            "IDR",
            "DEMO-TRANSFER",
            "demo-key-001",
            "corr-001"
        );

        assertThat(transaction.getStatus()).isEqualTo(TransactionState.RECEIVED);

        transaction.markValidating();
        transaction.markSubmitted();
        transaction.markSuccess("T24-ABC-123");

        assertThat(transaction.getStatus()).isEqualTo(TransactionState.SUCCESS);
        assertThat(transaction.getT24TransactionId()).isEqualTo("T24-ABC-123");
        assertThat(transaction.getUpdatedAt()).isNotNull();
    }

    @Test
    void idempotencyRecordTracksOriginalRequest() {
        IdempotencyRecord record = new IdempotencyRecord(
            "demo-key-002",
            "sha256:abc123",
            "TXN-20260922-000002",
            TransactionState.SUCCESS
        );

        assertThat(record.getIdempotencyKey()).isEqualTo("demo-key-002");
        assertThat(record.getStatus()).isEqualTo(TransactionState.SUCCESS);
        assertThat(record.getTransactionId()).isEqualTo("TXN-20260922-000002");
    }

    @Test
    void outboxEventCapturesBusinessEventIntent() {
        OutboxEvent event = new OutboxEvent(
            "transaction",
            "TXN-20260922-000003",
            "TransferSucceeded",
            "{\"event\":\"TransferSucceeded\"}"
        );

        assertThat(event.getAggregateType()).isEqualTo("transaction");
        assertThat(event.getEventType()).isEqualTo("TransferSucceeded");
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getCreatedAt()).isNotNull();
    }
}
