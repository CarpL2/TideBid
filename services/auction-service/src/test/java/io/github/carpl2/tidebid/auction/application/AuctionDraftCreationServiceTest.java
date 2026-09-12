package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionImageProperties;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionDraftCreationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T06:00:00.123456789Z");
    private static final String FIRST_KEY = "dev/users/42/202609/first.webp";
    private static final String SECOND_KEY = "dev/users/42/202609/second.png";

    private AuctionImageVerificationService verificationService;
    private AuctionDraftTransaction transaction;
    private AuctionDraftCreationService service;

    @BeforeEach
    void setUp() {
        verificationService = mock(AuctionImageVerificationService.class);
        transaction = mock(AuctionDraftTransaction.class);
        AtomicLong ids = new AtomicLong(200L);
        service = service(ids::incrementAndGet);
    }

    @Test
    void verifiesImagesThenCreatesNormalizedDraftAndOneToOneSession() {
        when(verificationService.verifyPendingUpload(42L, FIRST_KEY))
                .thenReturn(new AuctionImageVerificationService.VerifiedUpload(101L, FIRST_KEY));
        when(verificationService.verifyPendingUpload(42L, SECOND_KEY))
                .thenReturn(new AuctionImageVerificationService.VerifiedUpload(102L, SECOND_KEY));
        when(transaction.create(any(), any(), anyList(), any())).thenAnswer(invocation ->
                new AuctionDraftTransaction.CreatedDraft(
                        invocation.getArgument(0), invocation.getArgument(1), List.of()
                )
        );

        AuctionDraftTransaction.CreatedDraft created = service.create(validCommand());

        assertThat(created.item().id()).isEqualTo(201L);
        assertThat(created.item().title()).isEqualTo("Mechanical keyboard");
        assertThat(created.item().category()).isEqualTo("ELECTRONICS");
        assertThat(created.item().reviewStatus()).isEqualTo(AuctionItemReviewStatus.DRAFT);
        assertThat(created.session().id()).isEqualTo(202L);
        assertThat(created.session().itemId()).isEqualTo(created.item().id());
        assertThat(created.session().sellerId()).isEqualTo(created.item().sellerId());
        assertThat(created.session().status()).isEqualTo(AuctionSessionStatus.DRAFT);
        assertThat(created.session().startPrice()).isEqualTo(new BigDecimal("100.00"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuctionDraftTransaction.ImageBinding>> bindings =
                ArgumentCaptor.forClass(List.class);
        verify(transaction).create(any(), any(), bindings.capture(), any());
        assertThat(bindings.getValue()).containsExactly(
                new AuctionDraftTransaction.ImageBinding(101L, 42L, FIRST_KEY, 0),
                new AuctionDraftTransaction.ImageBinding(102L, 42L, SECOND_KEY, 1)
        );
    }

    @Test
    void rejectsInvalidTextCategoryAndConditionBeforeImageVerification() {
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.create(command(
                "x", "A valid description", "electronics", AuctionItemCondition.GOOD,
                new BigDecimal("100"), NOW.plusSeconds(120), NOW.plusSeconds(3600), List.of(FIRST_KEY)
        )));
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.create(command(
                "Valid title", "A valid description", "unknown", AuctionItemCondition.GOOD,
                new BigDecimal("100"), NOW.plusSeconds(120), NOW.plusSeconds(3600), List.of(FIRST_KEY)
        )));
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.create(command(
                "Valid title", "A valid description", "electronics", null,
                new BigDecimal("100"), NOW.plusSeconds(120), NOW.plusSeconds(3600), List.of(FIRST_KEY)
        )));
        verify(verificationService, never()).verifyPendingUpload(anyLong(), any());
    }

    @Test
    void rejectsInvalidAmountsAndTimeBoundsBeforeImageVerification() {
        assertError(AuctionErrorCode.AUCTION_AMOUNT_INVALID, () -> service.create(command(
                "Valid title", "A valid description", "electronics", AuctionItemCondition.GOOD,
                new BigDecimal("1.001"), NOW.plusSeconds(120), NOW.plusSeconds(3600), List.of(FIRST_KEY)
        )));
        assertError(AuctionErrorCode.AUCTION_TIME_INVALID, () -> service.create(command(
                "Valid title", "A valid description", "electronics", AuctionItemCondition.GOOD,
                new BigDecimal("100"), NOW.plusSeconds(59), NOW.plusSeconds(3600), List.of(FIRST_KEY)
        )));
        assertError(AuctionErrorCode.AUCTION_TIME_INVALID, () -> service.create(command(
                "Valid title", "A valid description", "electronics", AuctionItemCondition.GOOD,
                new BigDecimal("100"), NOW.plusSeconds(120), NOW.plus(Duration.ofDays(8)), List.of(FIRST_KEY)
        )));
        verify(verificationService, never()).verifyPendingUpload(anyLong(), any());
    }

    @Test
    void rejectsMissingDuplicateOrTooManyImagesBeforeObjectStorage() {
        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.create(withImages(List.of())));
        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.create(withImages(List.of(FIRST_KEY, FIRST_KEY))));
        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.create(withImages(List.of(
                FIRST_KEY, SECOND_KEY, "dev/users/42/3.webp", "dev/users/42/4.webp",
                "dev/users/42/5.webp", "dev/users/42/6.webp", "dev/users/42/7.webp",
                "dev/users/42/8.webp", "dev/users/42/9.webp", "dev/users/42/10.webp"
        ))));
        verify(verificationService, never()).verifyPendingUpload(anyLong(), any());
    }

    @Test
    void doesNotStartDatabaseTransactionWhenAnyImageVerificationFails() {
        when(verificationService.verifyPendingUpload(42L, FIRST_KEY))
                .thenReturn(new AuctionImageVerificationService.VerifiedUpload(101L, FIRST_KEY));
        when(verificationService.verifyPendingUpload(42L, SECOND_KEY))
                .thenThrow(new BusinessException(AuctionErrorCode.ASSET_ACCESS_DENIED));

        assertError(AuctionErrorCode.ASSET_ACCESS_DENIED, () -> service.create(validCommand()));
        verify(transaction, never()).create(any(), any(), anyList(), any());
    }

    @Test
    void mapsConcurrentImageBindingConflictAfterTransactionRollback() {
        when(verificationService.verifyPendingUpload(42L, FIRST_KEY))
                .thenReturn(new AuctionImageVerificationService.VerifiedUpload(101L, FIRST_KEY));
        when(verificationService.verifyPendingUpload(42L, SECOND_KEY))
                .thenReturn(new AuctionImageVerificationService.VerifiedUpload(102L, SECOND_KEY));
        when(transaction.create(any(), any(), anyList(), any()))
                .thenThrow(new AuctionDraftTransaction.ImageBindingConflictException("concurrent binding"));

        assertError(AuctionErrorCode.IMAGE_INVALID, () -> service.create(validCommand()));
    }

    private AuctionDraftCreationService service(IdGenerator idGenerator) {
        return new AuctionDraftCreationService(
                verificationService,
                transaction,
                idGenerator,
                new AuctionImageProperties(
                        java.util.Set.of("image/jpeg", "image/png", "image/webp"),
                        DataSize.ofMegabytes(10),
                        9
                ),
                new AuctionDraftFieldsValidator(
                        new AuctionTimingProperties(
                                Duration.ofMinutes(1), Duration.ofDays(7), true, Duration.ofSeconds(1), 50),
                        Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AuctionDraftCreationService.CreateDraftCommand validCommand() {
        return command(
                "  Mechanical keyboard  ", "A carefully maintained mechanical keyboard",
                "electronics", AuctionItemCondition.GOOD, new BigDecimal("100"),
                NOW.plusSeconds(120), NOW.plusSeconds(3600), List.of(FIRST_KEY, SECOND_KEY)
        );
    }

    private static AuctionDraftCreationService.CreateDraftCommand withImages(List<String> images) {
        AuctionDraftCreationService.CreateDraftCommand valid = validCommand();
        return new AuctionDraftCreationService.CreateDraftCommand(
                valid.sellerId(), valid.title(), valid.description(), valid.category(), valid.itemCondition(),
                valid.startPrice(), valid.bidIncrement(), valid.depositAmount(), valid.startAt(), valid.endAt(), images
        );
    }

    private static AuctionDraftCreationService.CreateDraftCommand command(
            String title,
            String description,
            String category,
            AuctionItemCondition condition,
            BigDecimal startPrice,
            Instant startAt,
            Instant endAt,
            List<String> images
    ) {
        return new AuctionDraftCreationService.CreateDraftCommand(
                42L, title, description, category, condition,
                startPrice, new BigDecimal("10"), new BigDecimal("50"), startAt, endAt, images
        );
    }

    private static void assertError(AuctionErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(errorCode));
    }
}
