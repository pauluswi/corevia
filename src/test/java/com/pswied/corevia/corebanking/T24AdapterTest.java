package com.pswied.corevia.corebanking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class T24AdapterTest {

    private final CoreBankingGateway gateway = new T24Adapter();

    @Test
    void shouldReturnCustomerAccountAndTransferMetadata() {
        Customer customer = gateway.getCustomer("CUST-001");
        Account account = gateway.getAccount("1000012345");
        TransferResult result = gateway.transfer(new TransferRequest(
            "1000012345",
            "2000098765",
            new BigDecimal("1500000"),
            "IDR",
            "DEMO-SUCCESS-001"
        ));

        assertThat(customer.customerId()).isEqualTo("CUST-001");
        assertThat(customer.status()).isEqualTo("ACTIVE");
        assertThat(account.accountId()).isEqualTo("1000012345");
        assertThat(account.currency()).isEqualTo("IDR");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("1500000"));
    }

    @Test
    void shouldResolveTransactionStatus() {
        TransactionStatus status = gateway.getTransactionStatus("TXN-123");

        assertThat(status.transactionId()).isEqualTo("TXN-123");
        assertThat(status.status()).isEqualTo("SUCCESS");
    }
}
