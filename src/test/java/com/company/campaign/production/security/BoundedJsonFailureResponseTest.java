package com.company.campaign.production.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact bounded JSON bytes of the production {@code 401} and {@code 403} responses.
 *
 * <p>The two envelopes are constants, so this proves no token, claim, issuer, subject or decoder text can ever
 * reach a client through a failure response and that the content type is JSON.
 */
class BoundedJsonFailureResponseTest {

    @Test
    void theAuthenticationEntryPointWritesABoundedJson401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new BoundedJsonAuthenticationEntryPoint().commence(
                new MockHttpServletRequest(), response, null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo(BoundedJsonAuthenticationEntryPoint.BODY);
        assertThat(response.getContentAsString()).doesNotContain("Bearer").doesNotContain("Exception");
    }

    @Test
    void theAccessDeniedHandlerWritesABoundedJson403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new BoundedJsonAccessDeniedHandler().handle(
                new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo(BoundedJsonAccessDeniedHandler.BODY);
        assertThat(response.getContentAsString()).doesNotContain("denied").doesNotContain("Exception");
    }
}