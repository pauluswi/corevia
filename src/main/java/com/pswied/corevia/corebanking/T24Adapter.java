package com.pswied.corevia.corebanking;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class T24Adapter implements CoreBankingGateway {

    private static final int MAX_ATTEMPTS = 3;
    private static final long CIRCUIT_BREAKER_TIMEOUT_MS = 30_000L;
    private static final long REQUEST_TIMEOUT_SECONDS = 2L;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0L;

    @Override
    public Customer getCustomer(String customerId) {
        return new Customer(customerId, "Sample Customer", "ACTIVE");
    }

    @Override
    public Account getAccount(String accountId) {
        return new Account(accountId, "CUST-001", "IDR", "ACTIVE", new BigDecimal("10000000"));
    }

    @Override
    public TransferResult transfer(TransferRequest request) {
        if (System.currentTimeMillis() < circuitOpenUntil) {
            throw new IllegalStateException("T24 circuit is open; retry later");
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                TransferResult result = executeWithTimeout(() -> {
                    String transactionId = "T24-" + UUID.randomUUID();
                    return new TransferResult(
                        transactionId,
                        "SUCCESS",
                        request.sourceAccount(),
                        request.destinationAccount(),
                        request.amount(),
                        request.currency()
                    );
                });
                consecutiveFailures.set(0);
                return result;
            } catch (Exception ex) {
                if (attempt == MAX_ATTEMPTS) {
                    if (consecutiveFailures.incrementAndGet() >= 3) {
                        circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_BREAKER_TIMEOUT_MS;
                    }
                    throw new IllegalStateException("T24 transfer failed after retries", ex);
                }
                sleepQuietly(attempt * 250L);
            }
        }

        throw new IllegalStateException("T24 transfer failed unexpectedly");
    }

    @Override
    public TransactionStatus getTransactionStatus(String transactionId) {
        return new TransactionStatus(transactionId, "SUCCESS", "Resolved by mock T24 adapter");
    }

    private <T> T executeWithTimeout(Callable<T> task) throws Exception {
        Future<T> future = executor.submit(task);
        try {
            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("T24 request timed out", e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
