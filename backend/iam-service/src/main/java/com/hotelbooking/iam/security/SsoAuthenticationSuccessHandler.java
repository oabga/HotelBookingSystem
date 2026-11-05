package com.hotelbooking.iam.security;

import com.hotelbooking.iam.config.SsoProperties;
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
    private final SsoProperties ssoProperties;

    public SsoAuthenticationSuccessHandler(SsoLoginService loginService, SsoProperties ssoProperties) {
        this.loginService = loginService;
        this.ssoProperties = ssoProperties;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        Object appAttr = request.getSession().getAttribute(SsoLoginService.SESSION_APP_ATTR);
        Object registerAttr = request.getSession().getAttribute(SsoLoginService.SESSION_REGISTER_ATTR);
        OidcUser oidcUser = extractOidcUser(authentication);
        if (!(appAttr instanceof String app) || oidcUser == null) {
            deny(request, response, HttpStatus.BAD_REQUEST, null);
            return;
        }
        try {
            String redirectUrl = loginService.completeLogin(oidcUser, app, Boolean.TRUE.equals(registerAttr));
            response.sendRedirect(redirectUrl);
        } catch (NotProvisionedException ex) {
            String errorRedirect = ssoProperties.isAllowed(app)
                    ? ssoProperties.callbackFor(app) + "#error=not_provisioned"
                    : null;
            deny(request, response, HttpStatus.FORBIDDEN, errorRedirect);
        }
    }

    private static OidcUser extractOidcUser(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser;
        }
        return null;
    }

    private static void deny(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String redirectUrl)
            throws IOException {
        SecurityContextHolder.clearContext();
        request.getSession().invalidate();
        if (redirectUrl != null) {
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(redirectUrl);
            return;
        }
        response.setStatus(status.value());
    }
}
