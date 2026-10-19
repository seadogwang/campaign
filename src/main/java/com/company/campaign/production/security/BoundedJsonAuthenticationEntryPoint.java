package com.company.campaign.production.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The bounded JSON {@code 401} response of the production bearer boundary.
 *
 * <p>It answers with one fixed code and no detail: no token, no claim, no issuer, no JWK Set URL, no decoder
 * exception text and no stack trace reach the client. An unauthenticated caller therefore cannot use a failure
 * response to probe the directory or the token contract.
 */
public final class BoundedJsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** The one bounded code of an unauthenticated production request. */
    public static final String CODE = "AUTHENTICATION_REQUIRED";

    static final String BODY = "{\"error\":\"" + CODE + "\"}";

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}