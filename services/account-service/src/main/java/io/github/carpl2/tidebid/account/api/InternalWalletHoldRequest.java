package io.github.carpl2.tidebid.account.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record InternalWalletHoldRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9:_-]{1,64}")
        String holdNo,

        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        String userId,

        @NotBlank
        @Size(max = 32)
        String businessType,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = false)
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount
) {
}
