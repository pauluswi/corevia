package com.pswied.corevia.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pswied.corevia.domain.IdempotencyRecord;
import com.pswied.corevia.domain.TransactionState;
import com.pswied.corevia.persistence.IdempotencyRecordRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyRecordRepository repository;

    @Test
    void claimCreatesRecordAndSubsequentClaimReturnsExisting() {
        IdempotencyQueryService queryService = new IdempotencyQueryService(repository);
        IdempotencyService service = new IdempotencyService(repository, queryService);

        String key = "claim-key-001";
        String fingerprint = "sha256:abc";
        String tx = "TXN-1";

        Optional<IdempotencyRecord> first = service.claimOrGetExisting(key, fingerprint, tx, TransactionState.RECEIVED);
        assertThat(first).isPresent();
        assertThat(first.get().getTransactionId()).isEqualTo(tx);

        Optional<IdempotencyRecord> second = service.claimOrGetExisting(key, fingerprint, tx, TransactionState.RECEIVED);
        assertThat(second).isPresent();
        assertThat(second.get().getTransactionId()).isEqualTo(tx);
    }
}
