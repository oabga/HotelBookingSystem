// Danh sách booking; USER thanh toán hoặc huỷ đơn của mình (contract §5.3, §5.5, §5.6).
(function () {
  "use strict";

  const api = typeof module !== "undefined" && module.exports ? require("./api.js") : window.stayHubApi;

  const statusLabels = {
    PENDING_PAYMENT: "Chờ thanh toán",
    CONFIRMED: "Đã xác nhận",
    CANCELLED: "Đã huỷ",
    EXPIRED: "Hết hạn"
  };

  const actions = {
    payment: {
      expectedStatus: "CONFIRMED",
      progress: "Đang thanh toán booking #",
      success: id => "Thanh toán mô phỏng thành công. Booking #" + id + " đã được xác nhận.",
      messages: {
        400: "ID booking không hợp lệ.",
        403: "Bạn không có quyền thanh toán booking này.",
        404: "Booking không tồn tại hoặc không thuộc phạm vi truy cập.",
        409: "Không thể thanh toán: đơn đã thanh toán, đã huỷ hoặc đã quá hạn.",
        network: "Không rõ kết quả thanh toán do mất kết nối. Hãy tải lại danh sách; không gửi lại tự động."
      }
    },
    cancel: {
      expectedStatus: "CANCELLED",
      progress: "Đang huỷ booking #",
      success: id => "Đã huỷ booking #" + id + " và trả phòng.",
      messages: {
        400: "ID booking không hợp lệ.",
        403: "Bạn không có quyền huỷ booking này.",
        404: "Booking không tồn tại hoặc không thuộc phạm vi truy cập.",
        409: "Không thể huỷ: chỉ huỷ được đơn đang chờ thanh toán.",
        network: "Không rõ kết quả huỷ do mất kết nối. Hãy tải lại danh sách; không gửi lại tự động."
      }
    }
  };

  function initBookings(doc, ctx) {
    const list = doc.getElementById("booking-list");
    const status = doc.getElementById("bookings-status");
    const actionStatus = doc.getElementById("booking-action-status");
    const reloadButton = doc.getElementById("bookings-reload");
    const now = ctx.now || (() => Date.now());
    let actionButtons = [];
    let busy = false;
    let loadSeq = 0;

    function setBusy(value) {
      busy = value;
      reloadButton.disabled = value;
      for (const button of actionButtons) button.disabled = value;
    }

    function renderCard(booking) {
      const item = api.element(doc, "li", undefined, "booking-card");
      const head = api.element(doc, "div", undefined, "card-head");
      head.append(
        api.element(doc, "strong", "Booking #" + booking.id),
        api.element(doc, "span", statusLabels[booking.status], "pill pill-" + booking.status.toLowerCase())
      );
      item.append(
        head,
        api.element(doc, "p", "Loại phòng #" + booking.roomTypeId + " · " + booking.roomCount + " phòng"),
        api.element(doc, "p", booking.checkIn + " → " + booking.checkOut),
        api.element(doc, "p", "Tổng tiền: " + api.formatMoney(booking.totalAmount))
      );
      if (ctx.role === "ADMIN") item.append(api.element(doc, "p", "Người đặt: " + booking.userEmail));
      if (booking.status !== "PENDING_PAYMENT") return item;

      const overdue = Date.parse(booking.paymentExpiresAt) <= now();
      item.append(api.element(doc, "p", overdue
        ? "Đã quá hạn thanh toán, đang chờ hệ thống cập nhật."
        : "Hạn thanh toán: " + api.formatTime(booking.paymentExpiresAt)));
      if (ctx.role !== "USER") return item;

      const row = api.element(doc, "div", undefined, "card-actions");
      if (!overdue) row.append(actionButton("Thanh toán", booking.id, "payment", "action-button small-button"));
      row.append(actionButton("Huỷ đơn", booking.id, "cancel", "action-button small-button ghost-button"));
      item.append(row);
      return item;
    }

    function actionButton(label, id, action, className) {
      const button = api.element(doc, "button", label, className);
      button.type = "button";
      button.disabled = busy;
      button.addEventListener("click", () => perform(id, action));
      actionButtons.push(button);
      return button;
    }

    async function load() {
      const seq = ++loadSeq;
      status.textContent = "Đang tải booking...";
      const result = await ctx.client.get("/bookings");
      if (seq !== loadSeq) return;
      const valid = result.kind === "ok" && Array.isArray(result.data)
        && result.data.every(booking => api.isBooking(booking, ctx.tenantId));
      if (!valid) {
        actionButtons = [];
        list.replaceChildren();
        status.textContent = api.describeFailure(result, { invalid: "Dữ liệu booking không hợp lệ. Không hiển thị danh sách." });
        return;
      }
      actionButtons = [];
      list.replaceChildren(...result.data.map(renderCard));
      status.textContent = result.data.length === 0 ? "Chưa có booking." : "Có " + result.data.length + " booking.";
    }

    async function perform(id, action) {
      if (busy) return;
      const config = actions[action];
      setBusy(true);
      actionStatus.textContent = config.progress + id + "...";
      const result = await ctx.client.post("/bookings/" + id + "/" + action);
      const valid = result.kind === "ok" && api.isBooking(result.data, ctx.tenantId)
        && result.data.id === id && result.data.status === config.expectedStatus;
      actionStatus.textContent = valid ? config.success(id) : api.describeFailure(result, {
        ...config.messages,
        invalid: "Response Booking không hợp lệ. Hãy tải lại danh sách; không gửi lại tự động."
      });
      setBusy(false);
      if (result.status !== 401) await load();
    }

    reloadButton.disabled = false;
    reloadButton.addEventListener("click", () => { if (!busy) load(); });
    return Object.freeze({ load });
  }

  if (typeof module !== "undefined" && module.exports) module.exports = { initBookings };
  if (typeof window !== "undefined") window.stayHubBookings = Object.freeze({ initBookings });
})();
