package com.pswied.corevia.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pswied.corevia.domain.IdempotencyRecord;
import com.pswied.corevia.domain.TransactionState;
import com.pswied.corevia.persistence.IdempotencyRecordRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private IdempotencyService service;

    @Test
    void concurrentClaimsProduceSingleRecord() throws Exception {
        String key = "concurrent-claim-001";
        String fingerprint = "sha256:concurrent";

        int threads = 2;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Optional<IdempotencyRecord>> results = new ArrayList<>();

        IntStream.range(0, threads).forEach(i -> ex.submit(() -> {
            try {
                start.await();
                // give different transaction ids to callers
                String tx = "TX-" + i;
                Optional<IdempotencyRecord> r = service.claimOrGetExisting(key, fingerprint, tx, TransactionState.RECEIVED);
                synchronized (results) {
                    results.add(r);
                }
            } catch (Exception e) {
                // ignore
            } finally {
                done.countDown();
            }
        }));

        // start all threads
        start.countDown();
        boolean finished = done.await(20, TimeUnit.SECONDS);
        ex.shutdown();
        ex.awaitTermination(1, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // verify exactly one record in DB and all returned present
        List<IdempotencyRecord> all = repository.findAll();
        assertThat(all).hasSize(1);
        IdempotencyRecord created = all.get(0);
        assertThat(results).isNotEmpty();
        results.forEach(opt -> assertThat(opt).isPresent());

        // all returned transaction ids should equal the created one
        results.forEach(opt -> assertThat(opt.get().getTransactionId()).isEqualTo(created.getTransactionId()));
    }
}
