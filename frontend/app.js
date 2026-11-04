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
    const canManage = role === "ADMIN";

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
        doc.getElementById("login-status").textContent = api.sessionExpired;
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
