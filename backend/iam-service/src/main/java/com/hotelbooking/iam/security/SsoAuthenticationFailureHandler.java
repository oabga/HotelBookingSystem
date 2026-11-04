package com.hotelbooking.iam.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * User cancelled Google login, or {@code state}/{@code code} was invalid. No JWT is minted; this
 * must never be reported as an SSO PASS (docs/api-contract.md §3.2).
 */
@Component
public class SsoAuthenticationFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        // setStatus, not sendError: sendError forwards to "/error", which is not permitAll and
        // would get hijacked by the OAuth2 entry point (redirecting to Google instead of 400).
        request.getSession().invalidate();
        response.setStatus(HttpStatus.BAD_REQUEST.value());
    }
}
