package io.github.carpl2.tidebid.auction.infrastructure.scheduling;

import io.github.carpl2.tidebid.auction.application.AuctionSessionOpeningService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionSessionOpeningJobTest {

    @Test
    void delegatesEachTriggerToTheOpeningService() {
        AuctionSessionOpeningService openingService = mock(AuctionSessionOpeningService.class);
        when(openingService.openDueSessions())
                .thenReturn(new AuctionSessionOpeningService.OpeningResult(0, 0, 0));

        new AuctionSessionOpeningJob(openingService).openDueSessions();

        verify(openingService).openDueSessions();
    }
}
