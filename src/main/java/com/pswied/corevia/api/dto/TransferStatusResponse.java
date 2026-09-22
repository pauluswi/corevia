package com.pswied.corevia.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferStatusResponse(
    String transactionId,
    String status,
    String sourceAccount,
    String destinationAccount,
    BigDecimal amount,
    String currency,
    String reference,
    Instant createdAt,
    Instant updatedAt
) {
}
