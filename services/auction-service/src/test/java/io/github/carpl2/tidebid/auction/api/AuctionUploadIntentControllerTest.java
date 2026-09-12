package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService;
import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService.UploadIntent;
import io.github.carpl2.tidebid.auction.application.AuctionUploadIntentService.UploadIntentCommand;
import io.github.carpl2.tidebid.auction.application.port.ObjectStoragePort;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.infrastructure.security.AuctionSecurityConfiguration;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.security.InvalidAccessTokenException;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.web.CommonWebAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionUploadIntentController.class)
@ActiveProfiles("local-db")
@Import({AuctionSecurityConfiguration.class, CommonWebAutoConfiguration.class})
class AuctionUploadIntentControllerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-09-12T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-12T00:10:00Z");
    private static final String REQUEST_BODY = """
            {
              "originalFilename": "camera.webp",
              "contentType": "image/webp",
              "contentLength": 4096,
              "checksumSha256": null,
              "userId": "999999"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionUploadIntentService uploadIntentService;

    @MockitoBean
    private JwtAccessTokenVerifier tokenVerifier;

    @BeforeEach
    void authenticateUserToken() {
        when(tokenVerifier.verify("valid-token")).thenReturn(new JwtClaims(
                "seller", 42L, Set.of(Role.USER), ISSUED_AT, ISSUED_AT.plusSeconds(1800), "token-12345678"
        ));
    }

    @Test
    void createsUploadIntentForAuthenticatedUserAndReturnsStringId() throws Exception {
        when(uploadIntentService.create(any())).thenReturn(new UploadIntent(
                9_007_199_254_740_993L,
                "dev/users/42/202609/image.webp",
                new ObjectStoragePort.SignedUpload(
                        URI.create("https://object-storage.invalid/upload?operation=upload"),
                        Map.of("Content-Type", "image/webp", "Content-Length", "4096"),
                        EXPIRES_AT
                )
        ));

        String response = mockMvc.perform(post("/api/assets/upload-intents")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Request-Id", "upload-request-001")
                        .header("X-Trace-Id", "client-trace-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "client-trace-1234"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.traceId").value("client-trace-1234"))
                .andExpect(jsonPath("$.data.imageId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.objectKey").value("dev/users/42/202609/image.webp"))
                .andExpect(jsonPath("$.data.uploadUrl")
                        .value("https://object-storage.invalid/upload?operation=upload"))
                .andExpect(jsonPath("$.data.requiredHeaders.Content-Type").value("image/webp"))
                .andExpect(jsonPath("$.data.requiredHeaders.Content-Length").value("4096"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-09-12T00:10:00Z"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var command = org.mockito.ArgumentCaptor.forClass(UploadIntentCommand.class);
        verify(uploadIntentService).create(command.capture());
        assertThat(command.getValue().ownerId()).isEqualTo(42L);
        assertThat(command.getValue().originalFilename()).isEqualTo("camera.webp");
        assertThat(response).doesNotContain("accessKeySecret", "999999");
    }

    @Test
    void rejectsMissingAuthenticationBeforeCreatingIntent() throws Exception {
        mockMvc.perform(post("/api/assets/upload-intents")
                        .header("X-Request-Id", "upload-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));

        org.mockito.Mockito.verifyNoInteractions(uploadIntentService);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        doThrow(new InvalidAccessTokenException()).when(tokenVerifier).verify("invalid-token");

        mockMvc.perform(post("/api/assets/upload-intents")
                        .header("Authorization", "Bearer invalid-token")
                        .header("X-Request-Id", "upload-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
    }

    @Test
    void rejectsMissingOrMalformedRequestId() throws Exception {
        mockMvc.perform(post("/api/assets/upload-intents")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        mockMvc.perform(post("/api/assets/upload-intents")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Request-Id", "bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void mapsInvalidImageAndUnavailableStorageToStableErrors() throws Exception {
        doThrow(new BusinessException(AuctionErrorCode.IMAGE_INVALID))
                .doThrow(new BusinessException(AuctionErrorCode.STORAGE_UNAVAILABLE))
                .when(uploadIntentService).create(any());

        mockMvc.perform(post("/api/assets/upload-intents")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Request-Id", "upload-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUCTION_IMAGE_INVALID"));

        mockMvc.perform(post("/api/assets/upload-intents")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Request-Id", "upload-request-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUCTION_STORAGE_UNAVAILABLE"));
    }
}
