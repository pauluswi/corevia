package com.pswied.corevia.api;

import com.pswied.corevia.api.dto.AccountResponse;
import com.pswied.corevia.api.dto.CustomerResponse;
import com.pswied.corevia.api.dto.TransferCreateRequest;
import com.pswied.corevia.api.dto.TransferCreateResponse;
import com.pswied.corevia.api.dto.TransferStatusResponse;
import com.pswied.corevia.application.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/customers/{customerId}")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId) {
        return ResponseEntity.ok(transferService.getCustomer(customerId));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountId) {
        return ResponseEntity.ok(transferService.getAccount(accountId));
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferCreateResponse> createTransfer(
        @Valid @RequestBody TransferCreateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        TransferCreateResponse response = transferService.createTransfer(request, idempotencyKey, correlationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/transfers/{transactionId}")
    public ResponseEntity<TransferStatusResponse> getTransferStatus(@PathVariable String transactionId) {
        return ResponseEntity.ok(transferService.getTransferStatus(transactionId));
    }
}
