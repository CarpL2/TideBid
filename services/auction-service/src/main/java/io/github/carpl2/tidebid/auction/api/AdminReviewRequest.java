package io.github.carpl2.tidebid.auction.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminReviewRequest(
        @NotNull Decision decision,
        @Min(1) int submissionVersion,
        @Size(max = 500) String comment
) {
    public enum Decision {
        APPROVE
    }
}
