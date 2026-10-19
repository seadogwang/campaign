package com.company.campaign.production.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The bounded JSON {@code 403} response of the production bearer boundary.
 *
 * <p>It answers with one fixed code and no detail: an authenticated caller who is refused a route learns nothing
 * about the membership directory, the trusted context or the token that produced the decision.
 */
public final class BoundedJsonAccessDeniedHandler implements AccessDeniedHandler {

    /** The one bounded code of an authenticated but refused production request. */
    public static final String CODE = "ACCESS_DENIED";

    static final String BODY = "{\"error\":\"" + CODE + "\"}";

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}