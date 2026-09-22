package com.pswied.corevia.api.dto;

import java.math.BigDecimal;

public record AccountResponse(
    String accountId,
    String customerId,
    String currency,
    BigDecimal availableBalance,
    String status
) {
}
