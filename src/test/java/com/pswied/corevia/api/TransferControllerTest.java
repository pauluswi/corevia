package com.pswied.corevia.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getCustomerEndpointReturnsCustomer() throws Exception {
        mockMvc.perform(get("/api/v1/customers/CUST-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customerId").value("CUST-001"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getAccountEndpointReturnsAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1000012345"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("1000012345"))
            .andExpect(jsonPath("$.currency").value("IDR"));
    }

    @Test
    void createTransferRequiresIdempotencyKey() throws Exception {
        String payload = "{"
            + "\"sourceAccount\":\"1000012345\","
            + "\"destinationAccount\":\"2000098765\","
            + "\"amount\":1500000.00,"
            + "\"currency\":\"IDR\","
            + "\"reference\":\"PAYROLL-202609\""
            + "}";

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createTransferReturnsCreatedTransaction() throws Exception {
        String payload = "{"
            + "\"sourceAccount\":\"1000012345\","
            + "\"destinationAccount\":\"2000098765\","
            + "\"amount\":1500000.00,"
            + "\"currency\":\"IDR\","
            + "\"reference\":\"PAYROLL-202609\""
            + "}";

        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "key-001")
                .header("X-Correlation-Id", "corr-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.transactionId").exists())
            .andExpect(jsonPath("$.correlationId").value("corr-001"));
    }
}
