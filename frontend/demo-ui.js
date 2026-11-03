(function () {
  "use strict";

  function validRoomId(value) {
    return /^[1-9]\d*$/.test(String(value).trim()) && Number.isSafeInteger(Number(value));
  }

  function validPrice(value) {
    const input = String(value).trim();
    const price = Number(input);
    return /^\d+(?:\.\d{1,2})?$/.test(input)
      && Number.isFinite(price) && price > 0 && price <= 99999999.99;
  }

  function validateBooking(roomId, checkIn, checkOut) {
    if (!validRoomId(roomId)) return "Nhập Room ID là số nguyên dương.";
    if (!checkIn || !checkOut) {
      return "Chọn đủ ngày và giờ check-in, check-out.";
    }
    const start = localToUtc(checkIn);
    const end = localToUtc(checkOut);
    if (!start || !end) return "Ngày giờ check-in hoặc check-out không hợp lệ.";
    if (Date.parse(end) <= Date.parse(start)) {
      return "Check-out phải sau check-in.";
    }
    return null;
  }

  function localToUtc(value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
    if (!match) return null;
    const [, year, month, day, hour, minute] = match.map(Number);
    if (year < 2000 || year > 9999) return null;
    const date = new Date(year, month - 1, day, hour, minute);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1
      || date.getDate() !== day || date.getHours() !== hour || date.getMinutes() !== minute) return null;
    return date.toISOString();
  }

  function isBookingResponse(value, roomId, tenantId) {
    const utc = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;
    return value && typeof value === "object" && !Array.isArray(value)
      && Number.isSafeInteger(value.id) && value.id > 0
      && Number.isSafeInteger(value.tenantId) && value.tenantId === tenantId
      && Number.isSafeInteger(value.roomId) && value.roomId === roomId
      && typeof value.userEmail === "string" && value.userEmail.length > 0
      && [value.checkIn, value.checkOut, value.createdAt].every(time =>
        typeof time === "string" && utc.test(time) && Number.isFinite(Date.parse(time)))
      && Date.parse(value.checkOut) > Date.parse(value.checkIn)
      && value.status === "CONFIRMED";
  }

  function validateNewRoom(name, price) {
    const trimmed = String(name).trim();
    if (!trimmed) return "Nhập tên phòng.";
    if (trimmed.length > 100) return "Tên phòng không được quá 100 ký tự.";
    if (!validPrice(price)) return "Giá phòng phải lớn hơn 0, không quá 99999999.99 và tối đa 2 chữ số thập phân.";
    return null;
  }

  function validatePriceChange(roomId, price) {
    if (!validRoomId(roomId)) return "Nhập Room ID là số nguyên dương.";
    if (!validPrice(price)) return "Giá mới phải lớn hơn 0, không quá 99999999.99 và tối đa 2 chữ số thập phân.";
    return null;
  }

  function initDemoUi(doc, auth, rooms, fetchBooking) {
    const signedIn = Boolean(auth && auth.bearerHeaders().Authorization);
    const role = signedIn && auth.getDisplayRole ? auth.getDisplayRole() : null;
    const canBook = role === "USER";
    const canManage = role === "ADMIN";

    doc.getElementById("booking-fields").disabled = !canBook;
    doc.getElementById("create-room-fields").disabled = !canManage;
    doc.getElementById("price-fields").disabled = !canManage;
    doc.getElementById("admin-panel").hidden = !canManage;
    doc.getElementById("actions-status").textContent = !signedIn
      ? "Đăng nhập để mở thao tác theo role."
      : "Role hiển thị: " + role + ". Quyền thực tế do backend xác minh.";

    let bookingPending = false;
    doc.getElementById("booking-form").addEventListener("submit", async event => {
      event.preventDefault();
      if (bookingPending) return;
      const status = doc.getElementById("booking-action-status");
      const headers = auth && typeof auth.bearerHeaders === "function" ? auth.bearerHeaders() : {};
      if (!headers.Authorization) {
        status.textContent = "Đăng nhập để đặt phòng.";
        return;
      }
      if (auth.getDisplayRole() !== "USER") {
        status.textContent = "Chỉ USER được đặt phòng.";
        return;
      }
      const selectedRoomId = rooms && typeof rooms.getSelectedRoomId === "function"
        ? rooms.getSelectedRoomId() : null;
      if (!Number.isSafeInteger(selectedRoomId) || selectedRoomId <= 0
        || doc.getElementById("book-room-id").value !== String(selectedRoomId)) {
        status.textContent = "Chọn phòng từ danh sách đã tải.";
        return;
      }
      const checkIn = doc.getElementById("check-in").value;
      const checkOut = doc.getElementById("check-out").value;
      const error = validateBooking(
        selectedRoomId, checkIn, checkOut
      );
      if (error) {
        status.textContent = error;
        return;
      }
      const tenantId = auth.getDisplayTenantId && auth.getDisplayTenantId();
      if (!Number.isSafeInteger(tenantId) || tenantId <= 0) {
        status.textContent = "Token không có tenant hợp lệ. Hãy đăng nhập lại.";
        return;
      }
      bookingPending = true;
      const button = doc.getElementById("booking-submit");
      button.disabled = true;
      status.textContent = "Đang gửi yêu cầu đặt phòng...";
      try {
        const response = await fetchBooking("http://localhost:8080/api/bookings", {
          method: "POST",
          headers: { Authorization: headers.Authorization, "Content-Type": "application/json" },
          body: JSON.stringify({ roomId: selectedRoomId, checkIn: localToUtc(checkIn), checkOut: localToUtc(checkOut) })
        });
        const errors = {
          400: "Dữ liệu đặt phòng không hợp lệ.",
          401: "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại.",
          403: "Bạn không có quyền đặt phòng.",
          404: "Phòng không tồn tại hoặc không thuộc phạm vi truy cập.",
          409: "Phòng đã được đặt trong khoảng thời gian này."
        };
        if (response.status === 201) {
          let booking;
          try { booking = await response.json(); } catch (_error) { /* handled below */ }
          status.textContent = isBookingResponse(booking, selectedRoomId, tenantId)
            ? "Đặt phòng thành công. Booking #" + booking.id + " — " + booking.status + "."
            : "Response Booking không hợp lệ; chưa thể xác nhận kết quả. Không gửi lại tự động.";
        } else {
          status.textContent = errors[response.status] || "Không thể đặt phòng do lỗi máy chủ (HTTP " + response.status + ").";
        }
      } catch (_error) {
        status.textContent = "Không kết nối được Booking API. Kiểm tra Gateway hoặc CORS; không gửi lại tự động.";
      } finally {
        bookingPending = false;
        button.disabled = false;
      }
    });

    let adminPending = false;
    async function sendAdminRoom(url, method, payload, expectedStatus, tenantId, expectedId, expectedName) {
      try {
        const response = await fetchBooking(url, {
          method,
          headers: { Authorization: auth.bearerHeaders().Authorization, "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });
        const errors = {
          400: "Dữ liệu phòng không hợp lệ.",
          401: "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại.",
          403: "Bạn không có quyền quản lý phòng.",
          404: "Phòng không tồn tại hoặc không thuộc tenant được phép."
        };
        if (response.status !== expectedStatus) {
          return { error: errors[response.status] || "Không thể cập nhật phòng do lỗi máy chủ (HTTP " + response.status + ")." };
        }
        let room;
        try { room = await response.json(); } catch (_error) { /* handled below */ }
        if (!rooms || typeof rooms.applyRoom !== "function"
          || !Number.isSafeInteger(room && room.id) || room.id <= 0
          || (expectedId !== null && room.id !== expectedId)
          || (expectedName !== null && room.name !== expectedName)
          || (expectedId === null && rooms.getLoadedRoomById(room.id))
          || !rooms.applyRoom(room, tenantId)) {
          return { error: "Response Room không hợp lệ; chưa thể cập nhật danh sách. Không gửi lại tự động." };
        }
        return { room };
      } catch (_error) {
        return { error: "Không kết nối được Rooms API. Kiểm tra Gateway hoặc CORS; không gửi lại tự động." };
      }
    }

    function adminAccessError() {
      const headers = auth && typeof auth.bearerHeaders === "function" ? auth.bearerHeaders() : {};
      if (!headers.Authorization) return "Đăng nhập để quản lý phòng.";
      if (auth.getDisplayRole() !== "ADMIN") return "Chỉ ADMIN được quản lý phòng.";
      const tenantId = auth.getDisplayTenantId && auth.getDisplayTenantId();
      if (!Number.isSafeInteger(tenantId) || tenantId <= 0) return "Token không có tenant hợp lệ. Hãy đăng nhập lại.";
      return null;
    }

    function setAdminLoading(loading) {
      doc.getElementById("create-room-submit").disabled = loading;
      doc.getElementById("price-submit").disabled = loading;
    }

    doc.getElementById("create-room-form").addEventListener("submit", async event => {
      event.preventDefault();
      if (adminPending) return;
      const status = doc.getElementById("admin-create-status");
      const accessError = adminAccessError();
      if (accessError) { status.textContent = accessError; return; }
      const name = doc.getElementById("new-room-name").value.trim();
      const priceInput = doc.getElementById("new-room-price").value;
      const error = validateNewRoom(name, priceInput);
      if (error) { status.textContent = error; return; }
      adminPending = true;
      setAdminLoading(true);
      status.textContent = "Đang thêm phòng...";
      try {
        const result = await sendAdminRoom("http://localhost:8080/api/rooms", "POST",
          { name, price: Number(priceInput) }, 201, auth.getDisplayTenantId(), null, null);
        status.textContent = result.error || "Đã thêm phòng #" + result.room.id + ".";
      } finally {
        adminPending = false;
        setAdminLoading(false);
      }
    });

    doc.getElementById("price-form").addEventListener("submit", async event => {
      event.preventDefault();
      if (adminPending) return;
      const status = doc.getElementById("admin-price-status");
      const accessError = adminAccessError();
      if (accessError) { status.textContent = accessError; return; }
      const id = rooms && typeof rooms.getSelectedPriceRoomId === "function"
        ? rooms.getSelectedPriceRoomId() : null;
      if (!Number.isSafeInteger(id) || id <= 0
        || doc.getElementById("price-room-id").value !== String(id)
        || !rooms.getLoadedRoomById(id)) {
        status.textContent = "Chọn phòng từ danh sách đã tải để sửa giá.";
        return;
      }
      const priceInput = doc.getElementById("room-price").value;
      const error = validatePriceChange(id, priceInput);
      if (error) { status.textContent = error; return; }
      adminPending = true;
      setAdminLoading(true);
      status.textContent = "Đang sửa giá phòng...";
      try {
        const result = await sendAdminRoom("http://localhost:8080/api/rooms/" + id + "/price", "PUT",
          { price: Number(priceInput) }, 200, auth.getDisplayTenantId(), id, rooms.getLoadedRoomById(id).name);
        status.textContent = result.error || "Đã sửa giá phòng #" + result.room.id + ".";
      } finally {
        adminPending = false;
        setAdminLoading(false);
      }
    });
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { validateBooking, validateNewRoom, validatePriceChange, localToUtc, isBookingResponse, initDemoUi };
  }
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    initDemoUi(document, window.stayHubAuth, window.stayHubRooms, window.fetch.bind(window));
  }
})();
