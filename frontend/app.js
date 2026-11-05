// Nối auth, client API và các phần giao diện theo role hiển thị từ JWT.
(function () {
  "use strict";

  const isNode = typeof module !== "undefined" && module.exports;
  const api = isNode ? require("./api.js") : window.stayHubApi;
  const { initBookings } = isNode ? require("./bookings.js") : window.stayHubBookings;
  const { initCatalog } = isNode ? require("./catalog.js") : window.stayHubCatalog;
  const { initAdmin } = isNode ? require("./admin.js") : window.stayHubAdmin;

  function initApp(doc, auth, fetchFn, now) {
    const signedIn = Boolean(auth.bearerHeaders().Authorization);
    const role = signedIn ? auth.getDisplayRole() : null;
    const appId = doc.body.dataset.appId;
    const adminPortal = appId === "app2";
    const canManage = role === "ADMIN";

    if (appId === "app1" && signedIn && role !== "USER") {
      const appContent = doc.getElementById("app-content");
      if (appContent) appContent.hidden = true;
      doc.getElementById("integration-status").textContent = "App 1 chỉ dành cho tài khoản USER.";
      return null;
    }

    if (adminPortal) {
      const authorized = signedIn && canManage;
      doc.getElementById("catalog-section").hidden = !authorized;
      doc.getElementById("bookings-section").hidden = !authorized;
      doc.getElementById("admin-section").hidden = !authorized;
      doc.getElementById("integration-status").textContent = !signedIn
        ? "App 2 dành cho ADMIN. Đăng nhập bằng Google để tiếp tục."
        : !canManage
          ? "Tài khoản hiện tại không có quyền ADMIN nên App 2 không gọi API quản trị."
          : "Đã xác thực giao diện ADMIN. Backend vẫn kiểm tra role và tenant cho từng API.";
      if (!authorized) return null;
    }

    doc.getElementById("admin-section").hidden = !canManage;
    doc.getElementById("bookings-title").textContent = canManage ? "Booking trong tenant" : "Booking của tôi";
    doc.getElementById("actions-note").textContent = canManage
      ? "ADMIN xem được booking trong tenant nhưng không đặt, thanh toán hoặc huỷ đơn."
      : "USER đặt phòng, thanh toán mô phỏng và huỷ đơn đang chờ thanh toán.";

    if (!signedIn) return null;

    const client = api.createClient({
      fetchFn,
      auth,
      onUnauthorized() {
        if (typeof auth.expireSession === "function") auth.expireSession(api.sessionExpired);
        else doc.getElementById("login-status").textContent = api.sessionExpired;
        if (adminPortal) {
          doc.getElementById("catalog-section").hidden = true;
          doc.getElementById("bookings-section").hidden = true;
          doc.getElementById("admin-section").hidden = true;
        }
      }
    });
    const ctx = { client, role, tenantId: auth.getDisplayTenantId(), now };
    const bookings = initBookings(doc, ctx);
    const catalog = initCatalog(doc, ctx, bookings);
    const admin = canManage ? initAdmin(doc, ctx) : null;

    return { ready: Promise.all([catalog.loadHotels(), bookings.load()]), catalog, bookings, admin };
  }

  if (isNode) module.exports = { initApp };
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    initApp(document, window.stayHubAuth, window.fetch.bind(window));
  }
})();
