package com.bank.accounts.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/v1/accounts/{accountId}/freeze. The reason stays in this service. */
public record FreezeRequest(@NotBlank @Size(max = 256) String reason) {
}
