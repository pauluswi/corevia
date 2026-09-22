package com.pswied.corevia.corebanking;

public interface CoreBankingGateway {

    Customer getCustomer(String customerId);

    Account getAccount(String accountId);

    TransferResult transfer(TransferRequest request);

    TransactionStatus getTransactionStatus(String transactionId);
}
