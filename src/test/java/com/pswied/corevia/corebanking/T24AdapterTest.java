package com.pswied.corevia.corebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class T24AdapterTest {

    private final CoreBankingGateway gateway = new T24Adapter();

    @Test
    void shouldReturnCustomerAccountAndTransferMetadata() {
        Customer customer = gateway.getCustomer("CUST-001");
        Account account = gateway.getAccount("1000012345");
        TransferResult result = gateway.transfer(new TransferRequest(
            "1000012345",
            "2000098765",
            new BigDecimal("1500000"),
            "IDR",
            "DEMO-SUCCESS-001"
        ));

        assertThat(customer.customerId()).isEqualTo("CUST-001");
        assertThat(customer.status()).isEqualTo("ACTIVE");
        assertThat(account.accountId()).isEqualTo("1000012345");
        assertThat(account.currency()).isEqualTo("IDR");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("1500000"));
    }

    @Test
    void shouldRetryTransientFailuresBeforeFailing() {
        AtomicInteger attempts = new AtomicInteger();
        T24Adapter adapter = new T24Adapter(
            request -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("transient failure");
                }
                return new TransferResult(
                    "T24-RETRY", "SUCCESS", request.sourceAccount(), request.destinationAccount(), request.amount(), request.currency());
            },
            Executors.newSingleThreadExecutor(),
            System::currentTimeMillis
        );

        TransferResult result = adapter.transfer(new TransferRequest(
            "1000012345",
            "2000098765",
            new BigDecimal("1000"),
            "IDR",
            "retry-001"
        ));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void shouldOpenCircuitAfterRepeatedFailures() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        T24Adapter adapter = new T24Adapter(
            request -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("persistent failure");
            },
            executor,
            System::currentTimeMillis
        );

        TransferRequest request = new TransferRequest(
            "1000012345",
            "2000098765",
            new BigDecimal("1000"),
            "IDR",
            "circuit-001"
        );

        assertThatThrownBy(() -> adapter.transfer(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("T24 transfer failed after retries");
        assertThatThrownBy(() -> adapter.transfer(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("T24 transfer failed after retries");
        assertThatThrownBy(() -> adapter.transfer(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("T24 transfer failed after retries");
        assertThatThrownBy(() -> adapter.transfer(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("T24 circuit is open");

        assertThat(attempts.get()).isGreaterThanOrEqualTo(9);
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldTimeoutWhenBankingCallDoesNotReturnInTime() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        T24Adapter adapter = new T24Adapter(
            request -> {
                started.countDown();
                Thread.sleep(5_000L);
                return new TransferResult("T24-TIMEOUT", "SUCCESS", request.sourceAccount(), request.destinationAccount(), request.amount(), request.currency());
            },
            executor,
            System::currentTimeMillis
        );

        assertThatThrownBy(() -> adapter.transfer(new TransferRequest(
            "1000012345",
            "2000098765",
            new BigDecimal("1000"),
            "IDR",
            "timeout-001"
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("T24 transfer failed after retries");

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();
    }

    @Test
    void shouldResolveTransactionStatus() {
        TransactionStatus status = gateway.getTransactionStatus("TXN-123");

        assertThat(status.transactionId()).isEqualTo("TXN-123");
        assertThat(status.status()).isEqualTo("SUCCESS");
    }
}
