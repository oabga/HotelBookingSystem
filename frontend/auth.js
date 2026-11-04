(function () {
  "use strict";

  const iamStartUrl = "http://localhost:8084/auth/start";

  function parseFragment(hash) {
    if (!hash || hash === "#") return { kind: "none" };
    const params = new URLSearchParams(hash.startsWith("#") ? hash.slice(1) : hash);
    if (params.has("error")) return { kind: "error", value: params.get("error") };
    if (params.has("token")) return { kind: "token", value: params.get("token") };
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
    if (appId !== "app1" && appId !== "app2") throw new Error("Unknown app");

    const loginButton = doc.getElementById("login-button");
    const loginStatus = doc.getElementById("login-status");
    const integrationStatus = doc.getElementById("integration-status");
    let jwt = null;
    let displayRole = null;
    let displayTenantId = null;

    loginButton.disabled = false;
    loginButton.addEventListener("click", () => {
      win.location.assign(iamStartUrl + "?app=" + appId);
    });

    const hash = win.location.hash;
    if (hash) {
      win.history.replaceState(win.history.state, "", win.location.pathname + win.location.search);
    }

    const fragment = parseFragment(hash);
    if (fragment.kind === "token") {
      try {
        const claims = decodeClaims(fragment.value);
        jwt = fragment.value;
        displayRole = claims.role;
        displayTenantId = claims.tenant_id;
        doc.getElementById("user-email").textContent = claims.email;
        doc.getElementById("user-role").textContent = claims.role;
        doc.getElementById("user-tenant").textContent = String(claims.tenant_id);
        loginStatus.textContent = "Đã nhận JWT; claims chỉ để hiển thị, frontend chưa xác minh chữ ký.";
        integrationStatus.textContent = "JWT trong memory; Hotels, Booking và ADMIN gọi qua Gateway.";
      } catch (_error) {
        loginStatus.textContent = "Token callback không hợp lệ. Hãy đăng nhập lại.";
      }
    } else if (fragment.kind === "error") {
      loginStatus.textContent = fragment.value === "not_provisioned"
        ? "Tài khoản chưa được IAM cấp quyền. Không có JWT."
        : fragment.value === "login_failed"
          ? "Đăng nhập Google thất bại hoặc đã hủy. Không có JWT."
          : "Đăng nhập thất bại. Không có JWT.";
    } else if (fragment.kind === "unknown") {
      loginStatus.textContent = "Callback không có kết quả đăng nhập hợp lệ.";
    } else {
      loginStatus.textContent = "Chưa đăng nhập. Nhấn Login with Google để chuyển tới IAM.";
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
