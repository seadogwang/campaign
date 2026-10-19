package com.company.campaign.localhealth;

import com.company.campaign.readiness.ReadinessPostgresVerification;
import com.company.campaign.readiness.ReadinessStorageMode;
import com.company.campaign.readiness.ReadinessStorageProbe;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The bounded HTTP slice of the local health endpoint: the exact success and error envelopes, the content
 * type, a deterministic request id and the absence of any leaked field.
 *
 * <p>This is a slice test, not API end-to-end evidence: it constructs the controller directly. The real
 * listening-server run is {@code LocalApiE2ETest}.
 */
class LocalHealthControllerTest {

    private static final String ENDPOINT = "/api/v1/health";

    @Test
    void exactSuccessEnvelopeForTheDefaultInMemoryState() throws Exception {
        mvc(ReadinessStorageMode.IN_MEMORY, ReadinessPostgresVerification.NOT_RUN)
                .perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("^req-[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.data.process").value("UP"))
                .andExpect(jsonPath("$.data.storageMode").value("IN_MEMORY"))
                .andExpect(jsonPath("$.data.postgresqlVerification").value("NOT_RUN"))
                .andExpect(jsonPath("$.data.readiness.level").value("LOCAL_READY"))
                .andExpect(jsonPath("$.data.readiness.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.readiness.evaluatedRequirements.length()").value(2))
                .andExpect(jsonPath("$.data.readiness.blockers.length()").value(2))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.actorRef").doesNotExist())
                .andExpect(jsonPath("$.data.url").doesNotExist())
                .andExpect(jsonPath("$.data.host").doesNotExist())
                .andExpect(jsonPath("$.data.evidence").doesNotExist());
    }

    @Test
    void aVerifiedPostgresqlRunReportsReadyForItsTwoRequirements() throws Exception {
        mvc(ReadinessStorageMode.POSTGRESQL, ReadinessPostgresVerification.PASSED)
                .perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageMode").value("POSTGRESQL"))
                .andExpect(jsonPath("$.data.postgresqlVerification").value("PASSED"))
                .andExpect(jsonPath("$.data.readiness.status").value("READY"))
                .andExpect(jsonPath("$.data.readiness.blockers.length()").value(0));
    }

    @Test
    void theRequestIdIsDeterministicForIdenticalRequests() throws Exception {
        MockMvc mvc = mvc(ReadinessStorageMode.IN_MEMORY, ReadinessPostgresVerification.NOT_RUN);

        String first = mvc.perform(get(ENDPOINT)).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get(ENDPOINT)).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(first).isEqualTo(second);
    }

    @Test
    void aQueryStringCannotChangeTheAnswerAndIsNeverEchoed() throws Exception {
        String body = mvc(ReadinessStorageMode.IN_MEMORY, ReadinessPostgresVerification.NOT_RUN)
                .perform(get(ENDPOINT + "?tenantId=tenant-9&environment=PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageMode").value("IN_MEMORY"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("tenant-9")
                .doesNotContain("PRODUCTION");
    }

    @Test
    void anUnsupportedMethodNeverReachesTheHandler() throws Exception {
        mvc(ReadinessStorageMode.IN_MEMORY, ReadinessPostgresVerification.NOT_RUN)
                .perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void anUnexpectedFailureProducesTheBoundedErrorEnvelopeAndNoExceptionText() throws Exception {
        ReadinessLocalHealthService failing = new ReadinessLocalHealthService(new ReadinessStorageProbe() {
            @Override
            public ReadinessStorageMode storageMode() {
                throw new IllegalStateException("jdbc:postgresql://leak.example:5432/x?password=leak");
            }

            @Override
            public ReadinessPostgresVerification postgresqlVerification() {
                return ReadinessPostgresVerification.NOT_RUN;
            }
        });

        String body = MockMvcBuilders.standaloneSetup(new LocalHealthController(failing))
                .build()
                .perform(get(ENDPOINT))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("LOCAL_HEALTH_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("IllegalStateException")
                .doesNotContain("jdbc:")
                .doesNotContain("password")
                .doesNotContain("leak.example");
    }

    private static MockMvc mvc(
            ReadinessStorageMode mode, ReadinessPostgresVerification verification) {
        ReadinessStorageProbe probe = new ReadinessStorageProbe() {
            @Override
            public ReadinessStorageMode storageMode() {
                return mode;
            }

            @Override
            public ReadinessPostgresVerification postgresqlVerification() {
                return verification;
            }
        };
        return MockMvcBuilders
                .standaloneSetup(new LocalHealthController(new ReadinessLocalHealthService(probe)))
                .build();
    }
}