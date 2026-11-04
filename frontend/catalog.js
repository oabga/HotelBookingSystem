// Chọn khách sạn, tìm phòng trống và đặt phòng (contract §4.2, §4.3, §5.2).
(function () {
  "use strict";

  const api = typeof module !== "undefined" && module.exports ? require("./api.js") : window.stayHubApi;

  const bookingMessages = {
    400: "Dữ liệu đặt phòng không hợp lệ.",
    403: "Bạn không có quyền đặt phòng, hoặc tài khoản chưa được cấp trong hệ thống.",
    404: "Loại phòng không tồn tại hoặc không thuộc phạm vi truy cập.",
    409: "Không đủ phòng ở ít nhất một đêm. Bấm Tìm kiếm để xem phòng còn trống.",
    invalid: "Response Booking không hợp lệ; chưa thể xác nhận kết quả. Xem Booking của tôi trước khi đặt lại.",
    network: "Không rõ đơn đã được tạo chưa do mất kết nối. Xem Booking của tôi trước khi đặt lại."
  };

  function initCatalog(doc, ctx, bookings) {
    const select = doc.getElementById("hotel-select");
    const checkIn = doc.getElementById("search-check-in");
    const checkOut = doc.getElementById("search-check-out");
    const fields = doc.getElementById("search-fields");
    const hotelsStatus = doc.getElementById("hotels-status");
    const searchStatus = doc.getElementById("search-status");
    const results = doc.getElementById("result-list");
    const bookingStatus = doc.getElementById("booking-status");
    let bookButtons = [];
    let bookingPending = false;
    let searchSeq = 0;

    function clearResults() {
      searchSeq += 1;
      bookButtons = [];
      results.replaceChildren();
    }

    function renderResult(room, stay) {
      const item = api.element(doc, "li", undefined, "room-card");
      item.append(
        api.element(doc, "strong", room.name),
        api.element(doc, "p", "Tối đa " + room.capacity + " khách · còn " + room.availableRooms + " phòng"),
        api.element(doc, "p", api.formatMoney(room.pricePerNight) + " / đêm"),
        api.element(doc, "p", room.nights + " đêm: " + api.formatMoney(room.totalPricePerRoom) + " / phòng")
      );
      if (ctx.role !== "USER") return item;

      const maxRooms = Math.min(10, room.availableRooms);
      const label = api.element(doc, "label", "Số phòng", "field");
      const count = doc.createElement("input");
      count.type = "number";
      count.min = "1";
      count.max = String(maxRooms);
      count.step = "1";
      count.value = "1";
      label.append(count);
      const button = api.element(doc, "button", "Đặt phòng", "action-button");
      button.type = "button";
      button.disabled = bookingPending;
      button.addEventListener("click", () => book(room, stay, count.value, maxRooms));
      bookButtons.push(button);
      item.append(label, button);
      return item;
    }

    async function search() {
      bookingStatus.textContent = "";
      const hotelId = Number(select.value);
      const stay = { checkIn: checkIn.value, checkOut: checkOut.value };
      clearResults();
      if (!Number.isSafeInteger(hotelId) || hotelId <= 0) {
        searchStatus.textContent = "Chọn khách sạn.";
        return;
      }
      const error = api.validateStay(stay.checkIn, stay.checkOut, { now: ctx.now ? new Date(ctx.now()) : undefined });
      if (error) {
        searchStatus.textContent = error;
        return;
      }
      const seq = searchSeq;
      searchStatus.textContent = "Đang tìm phòng...";
      const query = "?checkIn=" + encodeURIComponent(stay.checkIn) + "&checkOut=" + encodeURIComponent(stay.checkOut);
      const result = await ctx.client.get("/hotels/" + hotelId + "/availability" + query);
      if (seq !== searchSeq) return;
      if (result.kind !== "ok" || !Array.isArray(result.data) || !result.data.every(api.isAvailability)) {
        searchStatus.textContent = api.describeFailure(result, {
          400: "Khoảng ngày không hợp lệ.",
          404: "Khách sạn không tồn tại hoặc không thuộc phạm vi truy cập.",
          invalid: "Dữ liệu phòng trống không hợp lệ."
        });
        return;
      }
      results.replaceChildren(...result.data.map(room => renderResult(room, stay)));
      searchStatus.textContent = result.data.length === 0
        ? "Không có loại phòng nào còn trống ở mọi đêm trong kỳ ở."
        : "Có " + result.data.length + " loại phòng cho " + stay.checkIn + " → " + stay.checkOut + ".";
    }

    async function book(room, stay, rawCount, maxRooms) {
      if (bookingPending) return;
      if (!api.validateIntegerInRange(rawCount, 1, maxRooms)) {
        bookingStatus.textContent = "Số phòng phải là số nguyên từ 1 đến " + maxRooms + ".";
        return;
      }
      const roomCount = Number(rawCount);
      // Khoá mọi nút Đặt tới khi có response: gửi lại sẽ tạo đơn thứ hai (contract §5.2).
      bookingPending = true;
      for (const button of bookButtons) button.disabled = true;
      bookingStatus.textContent = "Đang gửi yêu cầu đặt phòng...";
      const result = await ctx.client.post("/bookings", {
        roomTypeId: room.roomTypeId, checkIn: stay.checkIn, checkOut: stay.checkOut, roomCount
      });
      bookingPending = false;

      const created = result.kind === "ok" && result.status === 201 && api.isBooking(result.data, ctx.tenantId)
        && result.data.roomTypeId === room.roomTypeId && result.data.checkIn === stay.checkIn
        && result.data.checkOut === stay.checkOut && result.data.roomCount === roomCount
        && result.data.status === "PENDING_PAYMENT";

      if (created) {
        bookingStatus.textContent = "Đã tạo booking #" + result.data.id + ", tổng " + api.formatMoney(result.data.totalAmount)
          + ". Thanh toán trước " + api.formatTime(result.data.paymentExpiresAt) + ".";
      } else {
        bookingStatus.textContent = api.describeFailure(result, bookingMessages);
      }
      // Số phòng trống hiển thị đã cũ; chỉ tìm lại khi người dùng bấm Tìm kiếm (contract §8).
      if (created || result.status === 409) {
        clearResults();
        searchStatus.textContent = "Bấm Tìm kiếm để xem số phòng trống mới nhất.";
      } else {
        for (const button of bookButtons) button.disabled = false;
      }
      if (result.status !== 401) await bookings.load();
    }

    async function loadHotels() {
      hotelsStatus.textContent = "Đang tải khách sạn...";
      const result = await ctx.client.get("/hotels");
      if (result.kind !== "ok" || !Array.isArray(result.data)
        || !result.data.every(hotel => api.isHotel(hotel, ctx.tenantId))) {
        hotelsStatus.textContent = api.describeFailure(result, {
          invalid: "Dữ liệu khách sạn không hợp lệ. Không hiển thị danh sách."
        });
        return [];
      }
      const placeholder = api.element(doc, "option", "Chọn khách sạn");
      placeholder.value = "";
      select.replaceChildren(placeholder, ...result.data.map(hotel => {
        const option = api.element(doc, "option", hotel.address ? hotel.name + " — " + hotel.address : hotel.name);
        option.value = String(hotel.id);
        return option;
      }));
      select.value = "";
      fields.disabled = result.data.length === 0;
      hotelsStatus.textContent = result.data.length === 0
        ? "Tenant này chưa có khách sạn."
        : "Chọn khách sạn và ngày ở, rồi bấm Tìm kiếm.";
      return result.data;
    }

    function staleResults() {
      if (results.children.length > 0) searchStatus.textContent = "Điều kiện đã đổi. Bấm Tìm kiếm để xem lại.";
      clearResults();
    }

    for (const input of [select, checkIn, checkOut]) input.addEventListener("change", staleResults);
    doc.getElementById("search-form").addEventListener("submit", async event => {
      event.preventDefault();
      await search();
    });
    const today = api.todayInVietnam(ctx.now ? new Date(ctx.now()) : undefined);
    checkIn.min = today;
    checkOut.min = today;

    return Object.freeze({ loadHotels });
  }

  if (typeof module !== "undefined" && module.exports) module.exports = { initCatalog };
  if (typeof window !== "undefined") window.stayHubCatalog = Object.freeze({ initCatalog });
})();
