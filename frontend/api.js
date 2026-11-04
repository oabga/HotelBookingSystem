// Gọi Gateway và kiểm tra dữ liệu theo docs/api-contract.md v3.0. Không xử lý giao diện ở đây.
(function () {
  "use strict";

  const apiBase = "http://localhost:8080/api";
  const hotelZone = "Asia/Ho_Chi_Minh";
  const maxNights = 30;
  const maxPrice = 99999999.99;
  const bookingStatuses = ["PENDING_PAYMENT", "CONFIRMED", "CANCELLED", "EXPIRED"];
  const sessionExpired = "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại.";
  const moneyFormatter = new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 });

  const isPositiveId = value => Number.isSafeInteger(value) && value > 0;
  const isText = value => typeof value === "string" && value.length > 0;
  const isMoney = value => typeof value === "number" && Number.isFinite(value) && value > 0;
  const isCount = value => Number.isSafeInteger(value) && value >= 0;
  const isUtcTime = value => typeof value === "string"
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) && Number.isFinite(Date.parse(value));

  function dayNumber(value) {
    if (typeof value !== "string") return null;
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) return null;
    const [year, month, day] = match.slice(1).map(Number);
    const time = Date.UTC(year, month - 1, day);
    const date = new Date(time);
    if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
    return time / 86400000;
  }

  const isStayDate = value => dayNumber(value) !== null;

  function todayInVietnam(now = new Date()) {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: hotelZone, year: "numeric", month: "2-digit", day: "2-digit"
    }).format(now);
  }

  // Trả về câu báo lỗi, hoặc null nếu hợp lệ. allowPast chỉ dùng cho màn xem tồn kho.
  function validateStay(checkIn, checkOut, { allowPast = false, now = new Date() } = {}) {
    if (!checkIn || !checkOut) return "Chọn đủ ngày nhận và ngày trả phòng.";
    const start = dayNumber(checkIn);
    const end = dayNumber(checkOut);
    if (start === null || end === null) return "Ngày không hợp lệ.";
    if (!allowPast && start < dayNumber(todayInVietnam(now))) return "Ngày nhận phòng không được ở quá khứ.";
    if (end <= start) return "Ngày trả phòng phải sau ngày nhận phòng.";
    if (end - start > maxNights) return "Khoảng ngày tối đa " + maxNights + " đêm.";
    return null;
  }

  function validatePrice(value) {
    const input = String(value).trim();
    const price = Number(input);
    return /^\d+(?:\.\d{1,2})?$/.test(input) && price > 0 && price <= maxPrice
      ? null : "Giá phải lớn hơn 0, không quá 99999999.99 và tối đa 2 chữ số thập phân.";
  }

  function validateIntegerInRange(value, min, max) {
    const input = String(value).trim();
    return /^\d+$/.test(input) && Number(input) >= min && Number(input) <= max;
  }

  function validateNewRoomType(name, capacity, basePrice, totalRooms) {
    const trimmed = String(name).trim();
    if (!trimmed) return "Nhập tên loại phòng.";
    if (trimmed.length > 100) return "Tên loại phòng không được quá 100 ký tự.";
    if (!validateIntegerInRange(capacity, 1, 20)) return "Số khách phải là số nguyên từ 1 đến 20.";
    const priceError = validatePrice(basePrice);
    if (priceError) return priceError;
    if (!validateIntegerInRange(totalRooms, 1, 1000)) return "Số phòng phải là số nguyên từ 1 đến 1000.";
    return null;
  }

  const isHotel = (value, tenantId) => Boolean(value) && isPositiveId(value.id) && value.tenantId === tenantId
    && isText(value.name) && typeof value.address === "string";

  const isAvailability = value => Boolean(value) && isPositiveId(value.roomTypeId) && isText(value.name)
    && isPositiveId(value.capacity) && isMoney(value.pricePerNight) && isPositiveId(value.nights)
    && isMoney(value.totalPricePerRoom) && isPositiveId(value.availableRooms);

  const isRoomType = (value, tenantId) => Boolean(value) && isPositiveId(value.id) && value.tenantId === tenantId
    && isPositiveId(value.hotelId) && isText(value.name) && isPositiveId(value.capacity)
    && isMoney(value.basePrice) && isPositiveId(value.totalRooms);

  const isInventoryRow = value => Boolean(value) && isPositiveId(value.roomTypeId) && isText(value.name)
    && isStayDate(value.stayDate) && isCount(value.total) && isCount(value.sold)
    && Number.isSafeInteger(value.available);

  const isBooking = (value, tenantId) => Boolean(value) && isPositiveId(value.id) && value.tenantId === tenantId
    && isPositiveId(value.roomTypeId) && isText(value.userEmail)
    && isStayDate(value.checkIn) && isStayDate(value.checkOut) && dayNumber(value.checkOut) > dayNumber(value.checkIn)
    && Number.isSafeInteger(value.roomCount) && value.roomCount >= 1 && value.roomCount <= 10
    && isMoney(value.totalAmount) && bookingStatuses.includes(value.status)
    && isUtcTime(value.paymentExpiresAt) && isUtcTime(value.createdAt);

  const ticketStatuses = ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"];

  const isTicket = (value, tenantId) => Boolean(value) && isPositiveId(value.id) && value.tenantId === tenantId
    && (value.bookingId === null || isPositiveId(value.bookingId)) && isText(value.requesterEmail)
    && isText(value.subject) && isText(value.description) && ticketStatuses.includes(value.status)
    && isUtcTime(value.createdAt) && isUtcTime(value.updatedAt)
    && (value.closedAt === null || isUtcTime(value.closedAt));

  const isMessage = value => Boolean(value) && isPositiveId(value.id) && isPositiveId(value.ticketId)
    && isPositiveId(value.authorUserId) && ["USER", "ADMIN"].includes(value.authorRole)
    && isText(value.body) && isUtcTime(value.createdAt);

  const isTicketDetail = (value, tenantId) => isTicket(value, tenantId) && Array.isArray(value.messages)
    && value.messages.every(message => isMessage(message) && message.ticketId === value.id);

  const isTicketPage = value => Boolean(value) && Array.isArray(value.items)
    && isCount(value.page) && isPositiveId(value.size) && isCount(value.totalElements) && isCount(value.totalPages);

  // Khớp quy tắc của support-service để báo lỗi rõ hơn; backend vẫn là nơi quyết định.
  function textProblem(text) {
    if (/<\s*[a-zA-Z/!?]/.test(text)) return "Nội dung không được chứa thẻ HTML.";
    if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/.test(text)) return "Nội dung có ký tự không hợp lệ.";
    return null;
  }

  function validateNewTicket(subject, description) {
    const title = String(subject).trim();
    const content = String(description).trim();
    if (!title) return "Nhập tiêu đề.";
    if (title.length > 200) return "Tiêu đề không được quá 200 ký tự.";
    if (/[\r\n]/.test(title)) return "Tiêu đề chỉ được có một dòng.";
    if (!content) return "Nhập nội dung.";
    if (content.length > 4000) return "Nội dung không được quá 4000 ký tự.";
    return textProblem(title) || textProblem(content);
  }

  function validateMessage(body) {
    const text = String(body).trim();
    if (!text) return "Nhập nội dung trả lời.";
    if (text.length > 4000) return "Nội dung không được quá 4000 ký tự.";
    return textProblem(text);
  }

  function createClient({ fetchFn, auth, onUnauthorized }) {
    async function send(method, path, body) {
      const authorization = auth.bearerHeaders().Authorization;
      if (!authorization) return { kind: "signed-out" };
      const headers = { Authorization: authorization, Accept: "application/json" };
      const init = { method, headers };
      if (body !== undefined) {
        headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(body);
      }
      let response;
      try {
        response = await fetchFn(apiBase + path, init);
      } catch (_error) {
        return { kind: "network" };
      }
      if (response.status === 401 && onUnauthorized) onUnauthorized();
      if (!response.ok) return { kind: "http", status: response.status };
      try {
        return { kind: "ok", status: response.status, data: await response.json() };
      } catch (_error) {
        return { kind: "invalid", status: response.status };
      }
    }
    return Object.freeze({
      get: path => send("GET", path),
      post: (path, body) => send("POST", path, body),
      put: (path, body) => send("PUT", path, body),
      patch: (path, body) => send("PATCH", path, body)
    });
  }

  // Chỉ dựa vào HTTP status, không đọc detail/title của ProblemDetail (contract §2).
  function describeFailure(result, messages = {}) {
    if (result.kind === "signed-out") return "Đăng nhập để tiếp tục.";
    if (result.kind === "network") return messages.network || "Không kết nối được Gateway. Kiểm tra backend.";
    if (result.kind === "invalid") return messages.invalid || "Response không phải JSON hợp lệ.";
    if (result.kind === "ok") return messages.invalid || "Response không đúng định dạng.";
    if (result.status === 401) return sessionExpired;
    return messages[result.status] || "Yêu cầu thất bại (HTTP " + result.status + ").";
  }

  const formatMoney = value => moneyFormatter.format(value) + " VND";

  function formatTime(value) {
    return new Date(value).toLocaleString("vi-VN", { timeZone: hotelZone, hour12: false });
  }

  function element(doc, tag, text, className) {
    const node = doc.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  }

  const api = Object.freeze({
    createClient, describeFailure, validateStay, validatePrice, validateNewRoomType, validateIntegerInRange,
    todayInVietnam, isHotel, isAvailability, isRoomType, isInventoryRow, isBooking,
    isTicket, isMessage, isTicketDetail, isTicketPage, validateNewTicket, validateMessage,
    formatMoney, formatTime, element, sessionExpired
  });

  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.stayHubApi = api;
})();
