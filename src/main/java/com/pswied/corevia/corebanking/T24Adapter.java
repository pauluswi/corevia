package com.pswied.corevia.corebanking;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class T24Adapter implements CoreBankingGateway {

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
        String transactionId = "T24-" + UUID.randomUUID();
        return new TransferResult(
            transactionId,
            "SUCCESS",
            request.sourceAccount(),
            request.destinationAccount(),
            request.amount(),
            request.currency()
        );
    }

    @Override
    public TransactionStatus getTransactionStatus(String transactionId) {
        return new TransactionStatus(transactionId, "SUCCESS", "Resolved by mock T24 adapter");
    }
}
