package com.hotelbooking.iam.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Runs right after Spring Security finishes the Google Authorization Code + PKCE exchange
 * (/login/oauth2/code/google). Reads the {@code app} stashed in the session by
 * {@link com.hotelbooking.iam.web.AuthController}, mints the internal JWT and redirects to that
 * app's {@code /callback/#token=...} (docs/api-contract.md §3.1/§3.2).
 */
@Component
public class SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final SsoLoginService loginService;

    public SsoAuthenticationSuccessHandler(SsoLoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        Object appAttr = request.getSession().getAttribute(SsoLoginService.SESSION_APP_ATTR);
        OidcUser oidcUser = extractOidcUser(authentication);
        if (!(appAttr instanceof String app) || oidcUser == null) {
            deny(request, response, HttpStatus.BAD_REQUEST);
            return;
        }
        try {
            String redirectUrl = loginService.completeLogin(oidcUser, app);
            response.sendRedirect(redirectUrl);
        } catch (NotProvisionedException ex) {
            deny(request, response, HttpStatus.FORBIDDEN);
        }
    }

    private static OidcUser extractOidcUser(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser;
        }
        return null;
    }

    private static void deny(HttpServletRequest request, HttpServletResponse response, HttpStatus status) {
        // setStatus, not sendError: sendError forwards to "/error", which is not permitAll and
        // would get hijacked by the OAuth2 entry point (redirecting to Google instead of 4xx).
        SecurityContextHolder.clearContext();
        request.getSession().invalidate();
        response.setStatus(status.value());
    }
}
