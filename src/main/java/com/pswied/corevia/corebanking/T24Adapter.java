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
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class T24Adapter implements CoreBankingGateway {

    @FunctionalInterface
    interface TransferOperation {
        TransferResult execute(TransferRequest request) throws Exception;
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final long CIRCUIT_BREAKER_TIMEOUT_MS = 30_000L;
    private static final long REQUEST_TIMEOUT_SECONDS = 2L;

    private final ExecutorService executor;
    private final TransferOperation transferOperation;
    private final LongSupplier clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0L;

    public T24Adapter() {
        this(request -> new TransferResult(
            "T24-" + UUID.randomUUID(),
            "SUCCESS",
            request.sourceAccount(),
            request.destinationAccount(),
            request.amount(),
            request.currency()
        ), Executors.newFixedThreadPool(4), System::currentTimeMillis);
    }

    T24Adapter(TransferOperation transferOperation, ExecutorService executor, LongSupplier clock) {
        this.transferOperation = transferOperation;
        this.executor = executor;
        this.clock = clock;
    }

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
        if (clock.getAsLong() < circuitOpenUntil) {
            throw new IllegalStateException("T24 circuit is open; retry later");
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                TransferResult result = executeWithTimeout(() -> transferOperation.execute(request));
                consecutiveFailures.set(0);
                return result;
            } catch (Exception ex) {
                if (attempt == MAX_ATTEMPTS) {
                    if (consecutiveFailures.incrementAndGet() >= 3) {
                        circuitOpenUntil = clock.getAsLong() + CIRCUIT_BREAKER_TIMEOUT_MS;
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
