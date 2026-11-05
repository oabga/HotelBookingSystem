package com.hotelbooking.iam.security;

import com.hotelbooking.iam.config.SsoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * User cancelled Google login, or {@code state}/{@code code} was invalid. No JWT is minted; this
 * must never be reported as an SSO PASS (docs/api-contract.md §3.2).
 */
@Component
public class SsoAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SsoAuthenticationFailureHandler.class);

    private final SsoProperties ssoProperties;

    public SsoAuthenticationFailureHandler(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String errorCode = exception instanceof OAuth2AuthenticationException oauthException
                ? oauthException.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        log.warn("Google OAuth authentication failed ({})", errorCode);

        HttpSession session = request.getSession(false);
        Object appAttribute = session == null ? null : session.getAttribute(SsoLoginService.SESSION_APP_ATTR);
        if (session != null) session.invalidate();

        if (appAttribute instanceof String app && ssoProperties.isAllowed(app)) {
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(ssoProperties.callbackFor(app) + "#error=login_failed");
            return;
        }
        response.setStatus(HttpStatus.BAD_REQUEST.value());
    }
}
