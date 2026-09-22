package com.pswied.corevia.application;

import com.pswied.corevia.api.dto.AccountResponse;
import com.pswied.corevia.api.dto.CustomerResponse;
import com.pswied.corevia.api.dto.TransferCreateRequest;
import com.pswied.corevia.api.dto.TransferCreateResponse;
import com.pswied.corevia.api.dto.TransferStatusResponse;
import com.pswied.corevia.corebanking.Account;
import com.pswied.corevia.corebanking.CoreBankingGateway;
import com.pswied.corevia.corebanking.Customer;
import com.pswied.corevia.corebanking.TransferRequest;
import com.pswied.corevia.corebanking.TransferResult;
import com.pswied.corevia.domain.IdempotencyRecord;
import com.pswied.corevia.domain.Transaction;
import com.pswied.corevia.domain.TransactionState;
import com.pswied.corevia.persistence.IdempotencyRecordRepository;
import com.pswied.corevia.persistence.TransactionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final CoreBankingGateway coreBankingGateway;
    private final TransactionRepository transactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public TransferService(
        CoreBankingGateway coreBankingGateway,
        TransactionRepository transactionRepository,
        IdempotencyRecordRepository idempotencyRecordRepository
    ) {
        this.coreBankingGateway = coreBankingGateway;
        this.transactionRepository = transactionRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    public CustomerResponse getCustomer(String customerId) {
        Customer customer = coreBankingGateway.getCustomer(customerId);
        return new CustomerResponse(customer.customerId(), customer.name(), customer.status());
    }

    public AccountResponse getAccount(String accountId) {
        Account account = coreBankingGateway.getAccount(accountId);
        return new AccountResponse(
            account.accountId(),
            account.customerId(),
            account.currency(),
            account.balance(),
            account.status()
        );
    }

    @Transactional
    public TransferCreateResponse createTransfer(
        TransferCreateRequest request,
        String idempotencyKey,
        String correlationId
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        validateRequest(request);

        String fingerprint = fingerprint(request);
        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
                throw new IllegalStateException("Idempotency key was previously used with a different request");
            }
            Transaction existingTransaction = transactionRepository.findByTransactionId(existing.get().getTransactionId())
                .orElseThrow(() -> new IllegalStateException("Existing idempotent transaction was not found"));
            return toCreateResponse(existingTransaction, correlationId);
        }

        String transactionId = "TXN-" + Instant.now().toEpochMilli();
        Transaction transaction = new Transaction(
            transactionId,
            request.sourceAccount(),
            request.destinationAccount(),
            request.amount(),
            request.currency(),
            request.reference(),
            idempotencyKey,
            correlationId
        );

        transaction.markValidating();
        transactionRepository.save(transaction);

        TransferResult result = coreBankingGateway.transfer(new TransferRequest(
            request.sourceAccount(),
            request.destinationAccount(),
            request.amount(),
            request.currency(),
            request.reference()
        ));

        transaction.markSubmitted();
        if ("SUCCESS".equalsIgnoreCase(result.status())) {
            transaction.markSuccess(result.transactionId());
        } else if ("FAILED".equalsIgnoreCase(result.status())) {
            transaction.markFailed("CORE_BANKING_FAILED", result.status());
        } else {
            transaction.markUnknown();
        }
        transactionRepository.save(transaction);

        idempotencyRecordRepository.save(new IdempotencyRecord(
            idempotencyKey,
            fingerprint,
            transactionId,
            transaction.getStatus()
        ));

        return toCreateResponse(transaction, correlationId);
    }

    public TransferStatusResponse getTransferStatus(String transactionId) {
        Transaction transaction = transactionRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new IllegalArgumentException("Transaction was not found"));

        return new TransferStatusResponse(
            transaction.getTransactionId(),
            transaction.getStatus().name(),
            transaction.getSourceAccount(),
            transaction.getDestinationAccount(),
            transaction.getAmount(),
            transaction.getCurrency(),
            transaction.getReference(),
            transaction.getCreatedAt(),
            transaction.getUpdatedAt()
        );
    }

    private void validateRequest(TransferCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.sourceAccount() == null || request.sourceAccount().isBlank()) {
            throw new IllegalArgumentException("sourceAccount is required");
        }
        if (request.destinationAccount() == null || request.destinationAccount().isBlank()) {
            throw new IllegalArgumentException("destinationAccount is required");
        }
        if (request.sourceAccount().equals(request.destinationAccount())) {
            throw new IllegalArgumentException("Source and destination accounts must be different");
        }
        if (request.amount() == null || request.amount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        if (!"IDR".equalsIgnoreCase(request.currency())) {
            throw new IllegalArgumentException("Unsupported currency");
        }
        if (request.reference() == null || request.reference().isBlank()) {
            throw new IllegalArgumentException("reference is required");
        }
        if (request.reference().length() > 50) {
            throw new IllegalArgumentException("Reference must be 50 characters or fewer");
        }
    }

    private static String fingerprint(TransferCreateRequest request) {
        String raw = request.sourceAccount() + "|" + request.destinationAccount() + "|"
            + request.amount() + "|" + request.currency() + "|" + request.reference();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private TransferCreateResponse toCreateResponse(Transaction transaction, String correlationId) {
        return new TransferCreateResponse(
            transaction.getTransactionId(),
            transaction.getStatus().name(),
            transaction.getSourceAccount(),
            transaction.getDestinationAccount(),
            transaction.getAmount(),
            transaction.getCurrency(),
            transaction.getReference(),
            correlationId,
            transaction.getCreatedAt()
        );
    }
}
