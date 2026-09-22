package com.pswied.corevia.corebanking;

import java.math.BigDecimal;

public record TransferResult(
    String transactionId,
    String status,
    String sourceAccount,
    String destinationAccount,
    BigDecimal amount,
    String currency
) {
}
