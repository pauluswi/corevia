package com.pswied.corevia.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TransferCreateRequest(
    @NotBlank(message = "sourceAccount is required") String sourceAccount,
    @NotBlank(message = "destinationAccount is required") String destinationAccount,
    @NotNull(message = "amount is required") @DecimalMin(value = "0.01", message = "Amount must be greater than zero") BigDecimal amount,
    @NotBlank(message = "currency is required") String currency,
    @NotBlank(message = "reference is required") @Size(max = 50, message = "Reference must be 50 characters or fewer") String reference
) {
}
