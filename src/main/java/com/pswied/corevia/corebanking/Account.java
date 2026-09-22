package com.pswied.corevia.corebanking;

import java.math.BigDecimal;

public record Account(String accountId, String customerId, String currency, String status, BigDecimal balance) {
}
