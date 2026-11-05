package com.hotelbooking.iam.web;

import com.hotelbooking.iam.config.SsoProperties;
import com.hotelbooking.iam.security.NotProvisionedException;
import com.hotelbooking.iam.security.SsoLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

/**
 * Entry point for the two demo frontends (docs/api-contract.md §3.1). {@code app} must be in the
 * allowlist or this returns 400 without ever redirecting to Google. If the browser already has a
 * valid IAM session (SSO across App 1/App 2), a fresh JWT is minted immediately without going back
 * through Google.
 */
@Controller
public class AuthController {

    private final SsoProperties ssoProperties;
    private final SsoLoginService loginService;

    public AuthController(SsoProperties ssoProperties, SsoLoginService loginService) {
        this.ssoProperties = ssoProperties;
        this.loginService = loginService;
    }

    @GetMapping("/auth/start")
    public void start(
            @RequestParam(name = "app", required = false) String app,
            @RequestParam(name = "mode", defaultValue = "login") String mode,
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException {
        if (!ssoProperties.isAllowed(app)) {
            // setStatus, not sendError: sendError forwards to "/error", which is not permitAll and
            // would get hijacked by the OAuth2 entry point (redirecting to Google instead of 400).
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }
        if (!"login".equals(mode) && !("register".equals(mode) && "app1".equals(app))) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }
        request.getSession(true).setAttribute(SsoLoginService.SESSION_APP_ATTR, app);
        request.getSession().setAttribute(SsoLoginService.SESSION_REGISTER_ATTR, "register".equals(mode));

        OidcUser existingUser = extractOidcUser(authentication);
        if (existingUser == null) {
            response.sendRedirect("/oauth2/authorization/google");
            return;
        }

        try {
            response.sendRedirect(loginService.completeLogin(existingUser, app, "register".equals(mode)));
        } catch (NotProvisionedException ex) {
            SecurityContextHolder.clearContext();
            request.getSession().invalidate();
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(ssoProperties.callbackFor(app) + "#error=not_provisioned");
        }
    }

    @GetMapping("/auth/logout")
    public void logout(
            @RequestParam(name = "app", required = false) String app,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        if (!ssoProperties.isAllowed(app)) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(ssoProperties.callbackFor(app) + "#logged_out");
    }

    private static OidcUser extractOidcUser(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser;
        }
        return null;
    }
}
