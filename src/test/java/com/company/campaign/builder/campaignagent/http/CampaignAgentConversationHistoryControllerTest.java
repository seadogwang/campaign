package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.builder.campaignagent.CampaignAgentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded read-only conversation-history HTTP adapter.
 *
 * <p>It proves the two {@code GET} routes answer with the one strict envelope, that the list limit is validated
 * before the service is reached, and that a refused or unexpected failure maps to a bounded code with no
 * exception text on the wire. No Spring context, model, Tool, network or file is touched.
 */
class CampaignAgentConversationHistoryControllerTest {

    private static final CampaignAgentConversationHistoryListResponse EMPTY =
            new CampaignAgentConversationHistoryListResponse(List.of());

    /** A host service the test drives explicitly; the list throws an assertion when it must not be reached. */
    private static final class StubService implements CampaignAgentConversationHistoryService {

        private final CampaignAgentConversationHistoryListResponse list;
        private final CampaignAgentTurnHttpResponse read;
        private final RuntimeException failure;

        private StubService(
                CampaignAgentConversationHistoryListResponse list,
                CampaignAgentTurnHttpResponse read,
                RuntimeException failure) {
            this.list = list;
            this.read = read;
            this.failure = failure;
        }

        @Override
        public CampaignAgentConversationHistoryListResponse list(int limit) {
            if (failure != null) {
                throw failure;
            }
            return list;
        }

        @Override
        public CampaignAgentTurnHttpResponse read(String conversationId, String runId) {
            if (failure != null) {
                throw failure;
            }
            return read;
        }
    }

    @Test
    void theListRouteAnswersWithTheOneSuccessEnvelope() {
        CampaignAgentConversationHistoryController controller =
                new CampaignAgentConversationHistoryController(new StubService(EMPTY, null, null));

        ResponseEntity<CampaignAgentHistoryEnvelope> response = controller.list(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).isSameAs(EMPTY);
        assertThat(response.getBody().requestId()).isEqualTo("req-history-list");
    }

    @Test
    void theListLimitIsValidatedBeforeTheServiceIsReached() {
        // A service that would report an internal failure if it were called proves the limit is refused first.
        StubService unreachable = new StubService(null, null, new IllegalStateException("must not be called"));
        CampaignAgentConversationHistoryController controller =
                new CampaignAgentConversationHistoryController(unreachable);

        assertThat(controller.list("0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.list("not-a-number").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.list("1000").getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(controller.list("25").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void aRefusedReadMapsToANotFoundEnvelopeWithoutDisclosingAnything() {
        StubService refused = new StubService(null, null, CampaignAgentException.of(
                CampaignAgentHttpErrorCodes.NOT_FOUND, "internal-only prose that must never be rendered"));
        CampaignAgentConversationHistoryController controller =
                new CampaignAgentConversationHistoryController(refused);

        ResponseEntity<CampaignAgentHistoryEnvelope> response =
                controller.read("conversation-1", "run-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo(CampaignAgentHttpErrorCodes.NOT_FOUND);
        assertThat(response.getBody().error().message()).doesNotContain("internal-only prose");
    }

    @Test
    void anUnexpectedFailureIsBoundedAndItsTextIsNeverRendered() {
        StubService broken = new StubService(null, null, new IllegalStateException("driver prose"));
        CampaignAgentConversationHistoryController controller =
                new CampaignAgentConversationHistoryController(broken);

        ResponseEntity<CampaignAgentHistoryEnvelope> response =
                controller.read("conversation-1", "run-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code())
                .isEqualTo(CampaignAgentHttpErrorCodes.INTERNAL_FAILURE);
        assertThat(response.getBody().error().message()).doesNotContain("driver prose");
    }
}