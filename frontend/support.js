// Support Center: USER xin đổi ngày cho đơn đã đặt và nhận thông báo; ADMIN duyệt hoặc từ chối phiếu trong tenant.
// Role chỉ điều khiển giao diện; backend mới là nơi xác minh quyền. Nội dung luôn hiển thị bằng textContent.
(function () {
  "use strict";

  const isNode = typeof module !== "undefined" && module.exports;
  const api = isNode ? require("./api.js") : window.stayHubApi;

  const pageSize = 10;
  const pollMillis = 15000;
  const statusLabels = { PENDING: "Chờ duyệt", APPROVED: "Đã duyệt", REJECTED: "Bị từ chối" };

  function initSupport(doc, auth, fetchFn, timers) {
    const signedIn = Boolean(auth.bearerHeaders().Authorization);
    const role = signedIn ? auth.getDisplayRole() : null;
    const isUser = role === "USER";
    const isAdmin = role === "ADMIN";

    doc.getElementById("create-panel").hidden = !isUser;
    doc.getElementById("notification-panel").hidden = !isUser;
    doc.getElementById("filter-wrap").hidden = !isAdmin;
    if (!signedIn) return null;

    doc.getElementById("requests-title").textContent = isAdmin ? "Phiếu cần xử lý" : "Phiếu của tôi";
    doc.getElementById("requests-note").textContent = isAdmin
      ? "Duyệt sẽ đổi ngày của đơn nếu lúc đó vẫn còn phòng. Kết quả được báo cho người gửi."
      : "Theo dõi trạng thái các yêu cầu đã gửi.";

    const tenantId = auth.getDisplayTenantId();
    let pollTimer = null;
    const client = api.createClient({
      fetchFn,
      auth,
      onUnauthorized() {
        if (typeof auth.expireSession === "function") auth.expireSession(api.sessionExpired);
        else doc.getElementById("login-status").textContent = api.sessionExpired;
        doc.getElementById("create-panel").hidden = true;
        doc.getElementById("notification-panel").hidden = true;
        doc.getElementById("filter-wrap").hidden = true;
        if (pollTimer !== null) {
          timers.clearInterval(pollTimer);
          pollTimer = null;
        }
      }
    });

    const bookingSelect = doc.getElementById("change-booking");
    const checkInInput = doc.getElementById("change-check-in");
    const checkOutInput = doc.getElementById("change-check-out");
    const descriptionInput = doc.getElementById("change-description");
    const checkButton = doc.getElementById("check-button");
    const quoteResult = doc.getElementById("quote-result");
    const changeSubmit = doc.getElementById("change-submit");
    const changeStatus = doc.getElementById("change-status");
    const bookingsNote = doc.getElementById("bookings-note");
    const list = doc.getElementById("request-list");
    const listStatus = doc.getElementById("requests-status");
    const decisionStatus = doc.getElementById("decision-status");
    const reloadButton = doc.getElementById("requests-reload");
    const filter = doc.getElementById("status-filter");
    const prevButton = doc.getElementById("prev-page");
    const nextButton = doc.getElementById("next-page");
    const pageInfo = doc.getElementById("page-info");
    const notificationList = doc.getElementById("notification-list");
    const notificationStatus = doc.getElementById("notification-status");
    const notificationCount = doc.getElementById("notification-count");

    let page = 0;
    let totalPages = 0;
    let busy = false;
    let listSeq = 0;
    let quoteSeq = 0;
    let validQuote = null;
    let bookings = [];
    let decisionButtons = [];
    let readButtons = [];
    let knownNotificationIds = null;

    function syncControls() {
      prevButton.disabled = busy || page <= 0;
      nextButton.disabled = busy || page >= totalPages - 1;
      pageInfo.textContent = totalPages > 0 ? "Trang " + (page + 1) + "/" + totalPages : "";
      reloadButton.disabled = busy;
      checkButton.disabled = busy;
      changeSubmit.disabled = busy || validQuote === null;
      for (const button of decisionButtons) button.disabled = busy;
      for (const button of readButtons) button.disabled = busy;
    }

    // Khoá mọi thao tác tới khi có response: gửi lại có thể tạo phiếu trùng hoặc duyệt hai lần.
    function setBusy(value) {
      busy = value;
      syncControls();
    }

    function resetQuote(message) {
      quoteSeq += 1;
      validQuote = null;
      quoteResult.textContent = message || "";
      syncControls();
    }

    // ---------- Gửi yêu cầu ----------

    async function loadBookings() {
      const result = await client.get("/bookings/reschedulable");
      if (result.kind !== "ok" || !Array.isArray(result.data) || !result.data.every(api.isReschedulable)) {
        bookingsNote.textContent = api.describeFailure(result, {
          403: "Tài khoản chưa được cấp quyền đổi ngày.",
          invalid: "Dữ liệu đơn đặt không hợp lệ."
        });
        return;
      }
      const previous = bookingSelect.value;
      bookings = result.data;
      const placeholder = api.element(doc, "option", "Chọn đơn đã đặt");
      placeholder.value = "";
      bookingSelect.replaceChildren(placeholder, ...bookings.map(booking => {
        const option = api.element(doc, "option", "#" + booking.id + " · " + booking.roomTypeName + " · " + booking.hotelName
          + " · " + booking.checkIn + " → " + booking.checkOut + " · " + booking.roomCount + " phòng");
        option.value = String(booking.id);
        return option;
      }));
      const stillThere = bookings.some(booking => String(booking.id) === previous);
      bookingSelect.value = stillThere ? previous : "";
      if (!stillThere) resetQuote();
      bookingsNote.textContent = bookings.length === 0
        ? "Bạn chưa có đơn nào đổi được ngày: đơn phải đã thanh toán và chưa tới ngày nhận phòng."
        : "";
    }

    function currentInput() {
      const booking = bookings.find(item => String(item.id) === bookingSelect.value);
      return { booking, checkIn: checkInInput.value, checkOut: checkOutInput.value };
    }

    function inputProblem({ booking, checkIn, checkOut }) {
      if (!booking) return "Chọn đơn đã đặt.";
      const error = api.validateStay(checkIn, checkOut);
      if (error) return error;
      if (booking.checkIn === checkIn && booking.checkOut === checkOut) return "Ngày mới phải khác ngày hiện tại.";
      return null;
    }

    async function checkAvailability() {
      if (busy) return;
      const input = currentInput();
      const problem = inputProblem(input);
      if (problem) {
        resetQuote(problem);
        return;
      }
      resetQuote("Đang kiểm tra phòng...");
      const seq = quoteSeq;
      const query = "?checkIn=" + encodeURIComponent(input.checkIn) + "&checkOut=" + encodeURIComponent(input.checkOut);
      const result = await client.get("/bookings/" + input.booking.id + "/reschedule-quote" + query);
      if (seq !== quoteSeq) return;
      if (result.kind !== "ok" || !api.isQuote(result.data)) {
        quoteResult.textContent = api.describeFailure(result, {
          400: "Ngày không hợp lệ cho đơn này.",
          404: "Không tìm thấy đơn đặt.",
          409: "Đơn này không còn đổi được ngày.",
          invalid: "Không đọc được kết quả kiểm tra phòng."
        });
        return;
      }
      if (!result.data.available) {
        quoteResult.textContent = "Không đủ phòng ở ít nhất một đêm trong khoảng ngày này. Hãy chọn ngày khác.";
        return;
      }
      validQuote = { bookingId: input.booking.id, checkIn: input.checkIn, checkOut: input.checkOut };
      quoteResult.textContent = "Còn phòng: " + result.data.nights + " đêm, " + result.data.roomCount + " phòng. Tổng tiền "
        + api.formatMoney(result.data.currentTotalAmount) + " → " + api.formatMoney(result.data.newTotalAmount)
        + ". Phòng chưa được giữ cho tới khi yêu cầu được duyệt.";
      syncControls();
    }

    async function submitChange() {
      if (busy || validQuote === null) return;
      const error = api.validateDescription(descriptionInput.value);
      if (error) {
        changeStatus.textContent = error;
        return;
      }
      const description = descriptionInput.value.trim();
      const body = { ...validQuote, description };
      setBusy(true);
      changeStatus.textContent = "Đang gửi yêu cầu...";
      const result = await client.post("/support/date-change-requests", body);
      setBusy(false);
      const created = result.status === 201 && api.isDateChangeRequest(result.data, tenantId)
        && result.data.status === "PENDING" && result.data.bookingId === body.bookingId
        && result.data.requestedCheckIn === body.checkIn && result.data.requestedCheckOut === body.checkOut;
      if (created) {
        checkInInput.value = "";
        checkOutInput.value = "";
        descriptionInput.value = "";
        bookingSelect.value = "";
        resetQuote();
        changeStatus.textContent = "Đã gửi phiếu #" + result.data.id + ", đang chờ duyệt.";
        page = 0;
        await loadRequests();
        return;
      }
      changeStatus.textContent = api.describeFailure(result, {
        400: "Nội dung yêu cầu không hợp lệ.",
        403: "Bạn không có quyền gửi yêu cầu, hoặc tài khoản chưa được cấp trong hệ thống.",
        404: "Không tìm thấy đơn đặt.",
        409: "Không gửi được: đơn đã có phiếu đang chờ duyệt, hoặc phòng vừa hết. Hãy kiểm tra lại.",
        503: "Hệ thống đặt phòng tạm thời không phản hồi. Chưa có phiếu nào được tạo.",
        invalid: "Response không hợp lệ; chưa thể xác nhận. Tải lại danh sách trước khi gửi lại.",
        network: "Không rõ phiếu đã được gửi chưa do mất kết nối. Tải lại danh sách trước khi gửi lại."
      });
      if (result.status === 409) resetQuote("Hãy kiểm tra lại phòng trước khi gửi.");
    }

    // ---------- Danh sách phiếu ----------

    function renderRequest(request) {
      const item = api.element(doc, "li", undefined, "booking-card");
      const head = api.element(doc, "div", undefined, "card-head");
      head.append(
        api.element(doc, "strong", "Phiếu #" + request.id + " · " + request.roomTypeName),
        api.element(doc, "span", statusLabels[request.status], "pill pill-" + request.status.toLowerCase())
      );
      item.append(head);
      if (isAdmin) item.append(api.element(doc, "p", "Người gửi: " + request.requesterEmail));
      item.append(
        api.element(doc, "p", "Booking #" + request.bookingId + " · " + request.roomCount + " phòng"),
        api.element(doc, "p", "Ngày hiện tại: " + request.currentCheckIn + " → " + request.currentCheckOut),
        api.element(doc, "p", "Ngày muốn đổi: " + request.requestedCheckIn + " → " + request.requestedCheckOut),
        api.element(doc, "p", "Tổng tiền: " + api.formatMoney(request.currentTotalAmount) + " → " + api.formatMoney(request.newTotalAmount)),
        api.element(doc, "p", request.description, "ticket-text")
      );
      if (request.decisionNote !== null) item.append(api.element(doc, "p", "Lý do: " + request.decisionNote, "ticket-text"));
      if (request.decidedAt !== null) item.append(api.element(doc, "p", "Xử lý lúc " + api.formatTime(request.decidedAt)));
      if (isAdmin && request.status === "PENDING") item.append(renderDecision(request));
      return item;
    }

    function renderDecision(request) {
      const box = api.element(doc, "div", undefined, "decision-box");
      const label = api.element(doc, "label", "Lý do từ chối (không bắt buộc)", "field");
      const note = doc.createElement("input");
      note.type = "text";
      note.maxLength = 1000;
      label.append(note);
      const row = api.element(doc, "div", undefined, "card-actions");
      const approve = api.element(doc, "button", "Duyệt", "action-button small-button");
      approve.type = "button";
      approve.addEventListener("click", () => decide(request.id, "approve"));
      const reject = api.element(doc, "button", "Từ chối", "action-button small-button ghost-button");
      reject.type = "button";
      reject.addEventListener("click", () => decide(request.id, "reject", note.value));
      approve.disabled = busy;
      reject.disabled = busy;
      decisionButtons.push(approve, reject);
      row.append(approve, reject);
      box.append(label, row);
      return box;
    }

    async function loadRequests() {
      const seq = ++listSeq;
      listStatus.textContent = "Đang tải phiếu...";
      let path = "/support/date-change-requests?page=" + page + "&size=" + pageSize;
      if (isAdmin && filter.value) path += "&status=" + encodeURIComponent(filter.value);
      const result = await client.get(path);
      if (seq !== listSeq) return;
      const valid = result.kind === "ok" && api.isPage(result.data)
        && result.data.items.every(request => api.isDateChangeRequest(request, tenantId));
      decisionButtons = [];
      if (!valid) {
        list.replaceChildren();
        totalPages = 0;
        syncControls();
        listStatus.textContent = api.describeFailure(result, {
          400: "Bộ lọc không hợp lệ.",
          403: "Tài khoản chưa được cấp quyền dùng Support Center.",
          invalid: "Dữ liệu phiếu không hợp lệ. Không hiển thị danh sách."
        });
        return;
      }
      totalPages = result.data.totalPages;
      list.replaceChildren(...result.data.items.map(renderRequest));
      syncControls();
      listStatus.textContent = result.data.totalElements === 0
        ? "Không có phiếu nào."
        : "Có " + result.data.totalElements + " phiếu.";
    }

    async function decide(id, action, rawNote) {
      if (busy) return;
      let body;
      if (action === "reject") {
        const note = String(rawNote || "").trim();
        const error = note ? api.validateNote(note) : null;
        if (error) {
          decisionStatus.textContent = error;
          return;
        }
        if (note) body = { note };
      }
      const expected = action === "approve" ? "APPROVED" : "REJECTED";
      setBusy(true);
      decisionStatus.textContent = action === "approve" ? "Đang duyệt phiếu #" + id + "..." : "Đang từ chối phiếu #" + id + "...";
      const result = await client.post("/support/date-change-requests/" + id + "/" + action, body);
      setBusy(false);
      if (result.kind === "ok" && api.isDateChangeRequest(result.data, tenantId)
        && result.data.id === id && result.data.status === expected) {
        decisionStatus.textContent = action === "approve"
          ? "Đã duyệt phiếu #" + id + " và đổi ngày cho đơn. Người gửi sẽ nhận thông báo."
          : "Đã từ chối phiếu #" + id + ". Người gửi sẽ nhận thông báo.";
      } else {
        decisionStatus.textContent = api.describeFailure(result, {
          400: "Lý do từ chối không hợp lệ.",
          403: "Bạn không có quyền xử lý phiếu này.",
          404: "Phiếu không tồn tại hoặc không thuộc phạm vi truy cập.",
          409: action === "approve"
            ? "Không duyệt được: ngày mới đã hết phòng, đơn không còn đổi được, hoặc phiếu đã được xử lý. Ngày của đơn chưa đổi; có thể từ chối phiếu."
            : "Không từ chối được: phiếu đã được xử lý, hoặc lần duyệt trước đã đổi ngày và cần duyệt lại để hoàn tất.",
          503: "Hệ thống đặt phòng tạm thời không phản hồi. Phiếu vẫn chờ duyệt; hãy thử lại sau.",
          invalid: "Response không hợp lệ; chưa thể xác nhận kết quả.",
          network: "Không rõ kết quả do mất kết nối. Xem lại danh sách trước khi thử lại."
        });
      }
      if (result.status !== 401) await loadRequests();
    }

    // ---------- Thông báo ----------

    function renderNotification(notification) {
      const unread = notification.readAt === null;
      const item = api.element(doc, "li", undefined, "message " + (unread ? "message-admin" : "message-user"));
      item.append(
        api.element(doc, "strong", notification.title),
        api.element(doc, "p", notification.body, "ticket-text"),
        api.element(doc, "span", api.formatTime(notification.createdAt), "message-meta")
      );
      if (unread) {
        const button = api.element(doc, "button", "Đánh dấu đã đọc", "action-button small-button ghost-button");
        button.type = "button";
        button.disabled = busy;
        button.addEventListener("click", () => markRead(notification.id));
        readButtons.push(button);
        item.append(button);
      }
      return item;
    }

    async function loadNotifications() {
      const result = await client.get("/notifications?page=0&size=" + pageSize);
      if (result.kind !== "ok" || !api.isPage(result.data) || !Number.isSafeInteger(result.data.unreadCount)
        || !result.data.items.every(api.isNotification)) {
        notificationStatus.textContent = api.describeFailure(result, {
          403: "Tài khoản chưa được cấp quyền xem thông báo.",
          invalid: "Dữ liệu thông báo không hợp lệ.",
          network: "Chưa tải được thông báo; sẽ thử lại sau."
        });
        return false;
      }
      const ids = result.data.items.map(item => item.id);
      const hasNew = knownNotificationIds !== null && ids.some(id => !knownNotificationIds.has(id));
      knownNotificationIds = new Set(ids);
      readButtons = [];
      notificationList.replaceChildren(...result.data.items.map(renderNotification));
      notificationCount.textContent = result.data.unreadCount > 0 ? result.data.unreadCount + " chưa đọc" : "";
      notificationStatus.textContent = result.data.totalElements === 0 ? "Chưa có thông báo." : "";
      return hasNew;
    }

    async function markRead(id) {
      if (busy) return;
      setBusy(true);
      const result = await client.post("/notifications/" + id + "/read");
      setBusy(false);
      if (result.kind !== "ok") {
        notificationStatus.textContent = api.describeFailure(result, {
          404: "Thông báo không tồn tại.",
          network: "Chưa đánh dấu được do mất kết nối."
        });
      }
      if (result.status !== 401) await loadNotifications();
    }

    // Có thông báo mới nghĩa là phiếu vừa được xử lý, nên cập nhật luôn phiếu và ngày của đơn.
    async function poll() {
      if (busy) return;
      const hasNew = await loadNotifications();
      if (hasNew && !busy) await Promise.all([loadRequests(), loadBookings()]);
    }

    // ---------- Sự kiện ----------

    for (const input of [bookingSelect, checkInInput, checkOutInput]) {
      input.addEventListener("change", () => resetQuote());
    }
    checkButton.addEventListener("click", () => checkAvailability());
    doc.getElementById("change-form").addEventListener("submit", async event => {
      event.preventDefault();
      await submitChange();
    });
    reloadButton.addEventListener("click", () => {
      if (busy) return;
      decisionStatus.textContent = "";
      loadRequests();
    });
    filter.addEventListener("change", () => {
      page = 0;
      decisionStatus.textContent = "";
      loadRequests();
    });
    prevButton.addEventListener("click", () => {
      if (busy || page <= 0) return;
      page -= 1;
      loadRequests();
    });
    nextButton.addEventListener("click", () => {
      if (busy || page >= totalPages - 1) return;
      page += 1;
      loadRequests();
    });

    const today = api.todayInVietnam();
    checkInInput.min = today;
    checkOutInput.min = today;
    syncControls();

    const startup = [loadRequests()];
    if (isUser) {
      startup.push(loadBookings(), loadNotifications());
      pollTimer = timers.setInterval(poll, pollMillis);
    }
    return Object.freeze({ ready: Promise.all(startup), poll });
  }

  if (isNode) module.exports = { initSupport };
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    initSupport(document, window.stayHubAuth, window.fetch.bind(window), window);
  }
})();
