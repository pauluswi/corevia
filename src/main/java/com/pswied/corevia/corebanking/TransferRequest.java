package com.pswied.corevia.corebanking;

import java.math.BigDecimal;

public record TransferRequest(
    String sourceAccount,
    String destinationAccount,
    BigDecimal amount,
    String currency,
    String reference
) {
}
