// Support Center: USER gửi và theo dõi ticket của mình; ADMIN xử lý ticket trong tenant.
// Role chỉ điều khiển giao diện; backend mới là nơi xác minh quyền. Nội dung luôn hiển thị bằng textContent.
(function () {
  "use strict";

  const isNode = typeof module !== "undefined" && module.exports;
  const api = isNode ? require("./api.js") : window.stayHubApi;

  const pageSize = 10;
  const statusLabels = {
    OPEN: "Mới",
    IN_PROGRESS: "Đang xử lý",
    RESOLVED: "Đã giải quyết",
    CLOSED: "Đã đóng"
  };
  const nextSteps = {
    OPEN: { status: "IN_PROGRESS", label: "Bắt đầu xử lý" },
    IN_PROGRESS: { status: "RESOLVED", label: "Đánh dấu đã giải quyết" },
    RESOLVED: { status: "CLOSED", label: "Đóng ticket" }
  };
  const authorLabels = { USER: "Khách hàng", ADMIN: "Bộ phận hỗ trợ" };
  const notFound = "Ticket không tồn tại hoặc không thuộc phạm vi truy cập.";

  function initSupport(doc, auth, fetchFn) {
    const signedIn = Boolean(auth.bearerHeaders().Authorization);
    const role = signedIn ? auth.getDisplayRole() : null;
    const isUser = role === "USER";
    const isAdmin = role === "ADMIN";

    doc.getElementById("create-panel").hidden = !isUser;
    if (!signedIn) return null;

    doc.getElementById("tickets-title").textContent = isAdmin ? "Ticket trong tenant" : "Ticket của tôi";
    doc.getElementById("tickets-note").textContent = isAdmin
      ? "ADMIN xem, trả lời và đổi trạng thái ticket trong tenant, nhưng không tạo ticket."
      : "Chọn một ticket để xem trao đổi và trả lời.";

    const tenantId = auth.getDisplayTenantId();
    const client = api.createClient({
      fetchFn,
      auth,
      onUnauthorized() {
        doc.getElementById("login-status").textContent = api.sessionExpired;
      }
    });

    const list = doc.getElementById("ticket-list");
    const listStatus = doc.getElementById("tickets-status");
    const reloadButton = doc.getElementById("tickets-reload");
    const prevButton = doc.getElementById("prev-page");
    const nextButton = doc.getElementById("next-page");
    const pageInfo = doc.getElementById("page-info");
    const detail = doc.getElementById("ticket-detail");
    const detailStatus = doc.getElementById("detail-status");
    const messageList = doc.getElementById("message-list");
    const replyForm = doc.getElementById("reply-form");
    const replyBody = doc.getElementById("reply-body");
    const replyStatus = doc.getElementById("reply-status");
    const replySubmit = doc.getElementById("reply-submit");
    const statusActions = doc.getElementById("status-actions");
    const statusButton = doc.getElementById("status-button");
    const createStatus = doc.getElementById("create-status");
    const createSubmit = doc.getElementById("create-submit");

    let page = 0;
    let totalPages = 0;
    let busy = false;
    let listSeq = 0;
    let detailSeq = 0;
    let current = null;
    let openButtons = [];

    function syncPager() {
      prevButton.disabled = busy || page <= 0;
      nextButton.disabled = busy || page >= totalPages - 1;
      pageInfo.textContent = totalPages > 0 ? "Trang " + (page + 1) + "/" + totalPages : "";
    }

    // Khoá mọi thao tác tới khi có response: gửi lại sẽ tạo thêm ticket hoặc tin nhắn.
    function setBusy(value) {
      busy = value;
      reloadButton.disabled = value;
      createSubmit.disabled = value;
      replySubmit.disabled = value;
      statusButton.disabled = value;
      for (const button of openButtons) button.disabled = value;
      syncPager();
    }

    function renderTicketCard(ticket) {
      const item = api.element(doc, "li", undefined, "booking-card");
      const head = api.element(doc, "div", undefined, "card-head");
      head.append(
        api.element(doc, "strong", "#" + ticket.id + " · " + ticket.subject),
        api.element(doc, "span", statusLabels[ticket.status], "pill pill-" + ticket.status.toLowerCase())
      );
      item.append(head);
      if (isAdmin) item.append(api.element(doc, "p", "Người gửi: " + ticket.requesterEmail));
      item.append(
        api.element(doc, "p", ticket.bookingId === null ? "Không gắn booking" : "Booking #" + ticket.bookingId),
        api.element(doc, "p", "Cập nhật: " + api.formatTime(ticket.updatedAt))
      );
      const button = api.element(doc, "button", "Xem", "action-button small-button ghost-button");
      button.type = "button";
      button.disabled = busy;
      button.addEventListener("click", () => {
        if (!busy) openTicket(ticket.id, true);
      });
      openButtons.push(button);
      item.append(button);
      return item;
    }

    async function loadTickets() {
      const seq = ++listSeq;
      listStatus.textContent = "Đang tải ticket...";
      const result = await client.get("/support/tickets?page=" + page + "&size=" + pageSize);
      if (seq !== listSeq) return;
      const valid = result.kind === "ok" && api.isTicketPage(result.data)
        && result.data.items.every(ticket => api.isTicket(ticket, tenantId));
      openButtons = [];
      if (!valid) {
        list.replaceChildren();
        totalPages = 0;
        syncPager();
        listStatus.textContent = api.describeFailure(result, {
          400: "Trang không hợp lệ.",
          403: "Tài khoản chưa được cấp quyền dùng Support Center.",
          invalid: "Dữ liệu ticket không hợp lệ. Không hiển thị danh sách."
        });
        return;
      }
      totalPages = result.data.totalPages;
      list.replaceChildren(...result.data.items.map(renderTicketCard));
      syncPager();
      listStatus.textContent = result.data.totalElements === 0
        ? "Chưa có ticket."
        : "Có " + result.data.totalElements + " ticket.";
    }

    function renderDetail(ticket) {
      current = ticket;
      detail.hidden = false;
      doc.getElementById("detail-title").textContent = "#" + ticket.id + " · " + ticket.subject;
      const pill = doc.getElementById("detail-status-pill");
      pill.textContent = statusLabels[ticket.status];
      pill.className = "pill pill-" + ticket.status.toLowerCase();

      const meta = ["Tạo lúc " + api.formatTime(ticket.createdAt)];
      if (isAdmin) meta.unshift("Người gửi: " + ticket.requesterEmail);
      meta.push(ticket.bookingId === null ? "Không gắn booking" : "Booking #" + ticket.bookingId);
      if (ticket.closedAt !== null) meta.push("Đóng lúc " + api.formatTime(ticket.closedAt));
      doc.getElementById("detail-meta").textContent = meta.join(" · ");
      doc.getElementById("detail-description").textContent = ticket.description;

      messageList.replaceChildren(...ticket.messages.map(message => {
        const item = api.element(doc, "li", undefined, "message message-" + message.authorRole.toLowerCase());
        item.append(
          api.element(doc, "span", authorLabels[message.authorRole] + " · " + api.formatTime(message.createdAt), "message-meta"),
          api.element(doc, "p", message.body, "ticket-text")
        );
        return item;
      }));

      const closed = ticket.status === "CLOSED";
      replyForm.hidden = closed;
      detailStatus.textContent = closed
        ? "Ticket đã đóng, không thể gửi thêm tin nhắn."
        : ticket.messages.length === 0 ? "Chưa có tin nhắn." : "";

      const step = nextSteps[ticket.status];
      statusActions.hidden = !isAdmin || !step;
      if (step) statusButton.textContent = step.label;
    }

    async function openTicket(id, freshView) {
      const seq = ++detailSeq;
      if (freshView) replyStatus.textContent = "";
      detailStatus.textContent = "Đang tải ticket...";
      const result = await client.get("/support/tickets/" + id);
      if (seq !== detailSeq) return;
      if (result.kind === "ok" && api.isTicketDetail(result.data, tenantId) && result.data.id === id) {
        renderDetail(result.data);
        return;
      }
      current = null;
      messageList.replaceChildren();
      replyForm.hidden = true;
      statusActions.hidden = true;
      detail.hidden = false;
      doc.getElementById("detail-title").textContent = "Ticket #" + id;
      doc.getElementById("detail-status-pill").textContent = "";
      doc.getElementById("detail-meta").textContent = "";
      doc.getElementById("detail-description").textContent = "";
      detailStatus.textContent = api.describeFailure(result, {
        400: "ID ticket không hợp lệ.",
        403: "Tài khoản chưa được cấp quyền dùng Support Center.",
        404: notFound,
        invalid: "Dữ liệu ticket không hợp lệ. Không hiển thị."
      });
    }

    async function sendReply() {
      if (busy || !current) return;
      const error = api.validateMessage(replyBody.value);
      if (error) {
        replyStatus.textContent = error;
        return;
      }
      const id = current.id;
      const text = replyBody.value.trim();
      setBusy(true);
      replyStatus.textContent = "Đang gửi...";
      const result = await client.post("/support/tickets/" + id + "/messages", { body: text });
      setBusy(false);
      if (result.status === 201 && api.isMessage(result.data) && result.data.ticketId === id) {
        replyBody.value = "";
        replyStatus.textContent = "Đã gửi tin nhắn.";
      } else {
        replyStatus.textContent = api.describeFailure(result, {
          400: "Nội dung không hợp lệ.",
          403: "Bạn không có quyền trả lời ticket này.",
          404: notFound,
          409: "Ticket đã đóng, không thể gửi thêm tin nhắn.",
          invalid: "Response không hợp lệ; chưa thể xác nhận. Xem lại trao đổi trước khi gửi lại.",
          network: "Không rõ tin nhắn đã được gửi chưa do mất kết nối. Xem lại trao đổi trước khi gửi lại."
        });
      }
      if (result.status !== 401) await Promise.all([openTicket(id, false), loadTickets()]);
    }

    async function advanceStatus() {
      if (busy || !current || !isAdmin) return;
      const step = nextSteps[current.status];
      if (!step) return;
      const id = current.id;
      setBusy(true);
      replyStatus.textContent = "Đang đổi trạng thái...";
      const result = await client.patch("/support/tickets/" + id + "/status", { status: step.status });
      setBusy(false);
      if (result.kind === "ok" && api.isTicket(result.data, tenantId)
        && result.data.id === id && result.data.status === step.status) {
        replyStatus.textContent = "Đã chuyển sang " + statusLabels[step.status] + ".";
      } else {
        replyStatus.textContent = api.describeFailure(result, {
          400: "Trạng thái không hợp lệ.",
          403: "Bạn không có quyền đổi trạng thái ticket.",
          404: notFound,
          409: "Không đổi được: trạng thái ticket vừa thay đổi. Đã tải lại ticket.",
          invalid: "Response không hợp lệ; chưa thể xác nhận trạng thái mới.",
          network: "Không rõ kết quả do mất kết nối. Xem lại ticket trước khi thử lại."
        });
      }
      if (result.status !== 401) await Promise.all([openTicket(id, false), loadTickets()]);
    }

    async function createTicket() {
      if (busy) return;
      const subjectInput = doc.getElementById("ticket-subject");
      const descriptionInput = doc.getElementById("ticket-description");
      const bookingSelect = doc.getElementById("ticket-booking");
      const error = api.validateNewTicket(subjectInput.value, descriptionInput.value);
      if (error) {
        createStatus.textContent = error;
        return;
      }
      const subject = subjectInput.value.trim();
      const description = descriptionInput.value.trim();
      const bookingId = bookingSelect.value ? Number(bookingSelect.value) : null;
      const body = { subject, description };
      if (bookingId !== null) body.bookingId = bookingId;

      setBusy(true);
      createStatus.textContent = "Đang gửi yêu cầu...";
      const result = await client.post("/support/tickets", body);
      setBusy(false);

      const created = result.status === 201 && api.isTicket(result.data, tenantId)
        && result.data.status === "OPEN" && result.data.subject === subject && result.data.bookingId === bookingId;
      if (created) {
        subjectInput.value = "";
        descriptionInput.value = "";
        bookingSelect.value = "";
        createStatus.textContent = "Đã tạo ticket #" + result.data.id + ".";
        // Danh sách theo id tăng dần nên ticket mới nằm ở trang cuối.
        await loadTickets();
        if (totalPages - 1 > page) {
          page = totalPages - 1;
          await loadTickets();
        }
        await openTicket(result.data.id, true);
        return;
      }
      createStatus.textContent = api.describeFailure(result, {
        400: "Nội dung ticket không hợp lệ.",
        403: "Bạn không có quyền tạo ticket, hoặc tài khoản chưa được cấp trong hệ thống.",
        404: "Booking không tồn tại hoặc không phải của bạn.",
        invalid: "Response không hợp lệ; chưa thể xác nhận. Tải lại danh sách trước khi gửi lại.",
        network: "Không rõ ticket đã được tạo chưa do mất kết nối. Tải lại danh sách trước khi gửi lại."
      });
    }

    async function loadBookingOptions() {
      const note = doc.getElementById("bookings-note");
      const result = await client.get("/bookings");
      if (result.kind !== "ok" || !Array.isArray(result.data)
        || !result.data.every(booking => api.isBooking(booking, tenantId))) {
        note.textContent = "Không tải được booking của bạn; vẫn có thể gửi ticket không gắn booking.";
        return;
      }
      const placeholder = api.element(doc, "option", "Không gắn booking");
      placeholder.value = "";
      doc.getElementById("ticket-booking").replaceChildren(placeholder, ...result.data.map(booking => {
        const option = api.element(doc, "option", "#" + booking.id + " · " + booking.checkIn + " → " + booking.checkOut);
        option.value = String(booking.id);
        return option;
      }));
      doc.getElementById("ticket-booking").value = "";
      note.textContent = result.data.length === 0 ? "Bạn chưa có booking nào để gắn." : "";
    }

    doc.getElementById("create-form").addEventListener("submit", async event => {
      event.preventDefault();
      await createTicket();
    });
    replyForm.addEventListener("submit", async event => {
      event.preventDefault();
      await sendReply();
    });
    statusButton.addEventListener("click", () => advanceStatus());
    reloadButton.addEventListener("click", () => {
      if (!busy) loadTickets();
    });
    prevButton.addEventListener("click", () => {
      if (busy || page <= 0) return;
      page -= 1;
      loadTickets();
    });
    nextButton.addEventListener("click", () => {
      if (busy || page >= totalPages - 1) return;
      page += 1;
      loadTickets();
    });
    syncPager();
    reloadButton.disabled = false;

    const startup = [loadTickets()];
    if (isUser) startup.push(loadBookingOptions());
    return Object.freeze({ ready: Promise.all(startup), loadTickets, openTicket });
  }

  if (isNode) module.exports = { initSupport };
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    initSupport(document, window.stayHubAuth, window.fetch.bind(window));
  }
})();
