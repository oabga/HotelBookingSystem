(function () {
  "use strict";

  const iamStartUrl = "http://localhost:8084/auth/start";

  function parseFragment(hash) {
    if (!hash || hash === "#") return { kind: "none" };
    const params = new URLSearchParams(hash.startsWith("#") ? hash.slice(1) : hash);
    if (params.has("error")) return { kind: "error", value: params.get("error") };
    if (params.has("token")) return { kind: "token", value: params.get("token") };
    if (params.has("logged_out")) return { kind: "logged_out" };
    return { kind: "unknown" };
  }

  function decodeClaims(token) {
    if (typeof token !== "string" || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)) {
      throw new Error("Invalid JWT shape");
    }
    const encoded = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const bytes = Uint8Array.from(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, "=")), char => char.charCodeAt(0));
    const claims = JSON.parse(new TextDecoder().decode(bytes));
    if (!claims || typeof claims !== "object" || Array.isArray(claims)
      || typeof claims.email !== "string" || !claims.email
      || !Number.isSafeInteger(claims.tenant_id) || claims.tenant_id <= 0
      || !["USER", "ADMIN"].includes(claims.role)) {
      throw new Error("Missing display claims");
    }
    return claims;
  }

  function initAuth(win, doc) {
    const appId = doc.body.dataset.appId;
    if (!["app1", "app2", "app3"].includes(appId)) throw new Error("Unknown app");

    const loginButton = doc.getElementById("login-button");
    const registerButton = doc.getElementById("register-button");
    const loginDescription = doc.getElementById("login-description");
    const loginActions = doc.getElementById("login-actions");
    const logoutForm = doc.getElementById("logout-form");
    const appContent = doc.getElementById("app-content");
    const loginStatus = doc.getElementById("login-status");
    const integrationStatus = doc.getElementById("integration-status");
    let jwt = null;
    let displayRole = null;
    let displayTenantId = null;

    loginButton.disabled = false;
    const startGoogleFlow = mode => {
      win.location.assign(iamStartUrl + "?app=" + appId + "&mode=" + mode);
    };
    loginButton.addEventListener("click", event => {
      if (event && typeof event.preventDefault === "function") event.preventDefault();
      startGoogleFlow("login");
    });
    if (registerButton) {
      registerButton.disabled = false;
      registerButton.addEventListener("click", event => {
        if (event && typeof event.preventDefault === "function") event.preventDefault();
        startGoogleFlow("register");
      });
    }

    const hash = win.location.hash;
    if (hash) {
      win.history.replaceState(win.history.state, "", win.location.pathname + win.location.search);
    }

    const fragment = parseFragment(hash);
    if (fragment.kind === "token") {
      try {
        const claims = decodeClaims(fragment.value);
        displayRole = claims.role;
        displayTenantId = claims.tenant_id;
        const authorizedForApp = appId !== "app1" || claims.role === "USER";
        jwt = authorizedForApp ? fragment.value : null;
        if (appContent) appContent.hidden = !authorizedForApp;
        if (loginDescription) loginDescription.hidden = true;
        if (loginActions) loginActions.hidden = true;
        if (logoutForm) logoutForm.hidden = false;
        doc.getElementById("user-email").textContent = claims.email;
        doc.getElementById("user-role").textContent = claims.role;
        doc.getElementById("user-tenant").textContent = String(claims.tenant_id);
        loginStatus.hidden = false;
        loginStatus.textContent = authorizedForApp
          ? "Bạn đã đăng nhập với " + claims.email + "."
          : "Tài khoản này không dùng được ở App 1. Tài khoản quản trị hãy vào App Admin.";
        integrationStatus.textContent = "Đã đăng nhập. Bạn có thể tìm phòng và quản lý booking của mình.";
      } catch (_error) {
        loginStatus.hidden = false;
        loginStatus.textContent = "Token callback không hợp lệ. Hãy đăng nhập lại.";
      }
    } else if (fragment.kind === "error") {
      loginStatus.hidden = false;
      loginStatus.textContent = fragment.value === "not_provisioned"
        ? appId === "app2"
          ? "Email này không được cấp quyền ADMIN cho ứng dụng quản trị."
          : appId === "app1"
            ? "App 1 chỉ dành cho tài khoản USER. Tài khoản quản trị hãy đăng nhập tại App Admin."
            : "Tài khoản chưa được tạo. Hãy tiếp tục với Google ở App 1 trước."
        : fragment.value === "login_failed"
          ? "Đăng nhập Google thất bại hoặc đã hủy. Không có JWT."
          : "Đăng nhập thất bại. Không có JWT.";
    } else if (fragment.kind === "unknown") {
      loginStatus.hidden = false;
      loginStatus.textContent = "Callback không có kết quả đăng nhập hợp lệ.";
    } else if (fragment.kind === "logged_out") {
      loginStatus.hidden = false;
      loginStatus.textContent = "Đã đăng xuất. Bạn có thể chọn tài khoản Google khác.";
    }

    return Object.freeze({
      getDisplayRole() {
        return displayRole;
      },
      getDisplayTenantId() {
        return displayTenantId;
      },
      bearerHeaders() {
        return jwt ? { Authorization: "Bearer " + jwt } : {};
      },
      expireSession(message) {
        jwt = null;
        displayRole = null;
        displayTenantId = null;
        if (appContent) appContent.hidden = true;
        if (loginDescription) loginDescription.hidden = false;
        if (loginActions) loginActions.hidden = false;
        if (logoutForm) logoutForm.hidden = true;
        for (const [id, value] of [
          ["user-email", "Chưa đăng nhập"],
          ["user-role", "Chưa xác định"],
          ["user-tenant", "Chưa xác định"]
        ]) {
          const element = doc.getElementById(id);
          if (element) element.textContent = value;
        }
        if (loginStatus) {
          loginStatus.hidden = false;
          loginStatus.textContent = message || "API không chấp nhận phiên đăng nhập. Hãy đăng nhập lại.";
        }
      }
    });
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { parseFragment, decodeClaims, initAuth };
  }
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    window.stayHubAuth = initAuth(window, document);
  }
})();
