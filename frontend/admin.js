// ADMIN: xem tồn kho theo đêm, thêm loại phòng, sửa giá (contract §4.4, §4.5, §4.6).
// Role chỉ điều khiển giao diện; backend mới là nơi xác minh quyền.
(function () {
  "use strict";

  const api = typeof module !== "undefined" && module.exports ? require("./api.js") : window.stayHubApi;

  function initAdmin(doc, ctx) {
    const hotelSelect = doc.getElementById("hotel-select");
    const inventoryFrom = doc.getElementById("inventory-check-in");
    const inventoryTo = doc.getElementById("inventory-check-out");
    const inventoryStatus = doc.getElementById("inventory-status");
    const inventoryRows = doc.getElementById("inventory-rows");
    const priceSelect = doc.getElementById("price-room-type");
    const priceInput = doc.getElementById("new-price");
    const priceStatus = doc.getElementById("admin-price-status");
    const createStatus = doc.getElementById("admin-create-status");
    const createButton = doc.getElementById("create-room-type-submit");
    const priceButton = doc.getElementById("price-submit");
    const roomTypes = new Map();
    let inventorySeq = 0;
    let writing = false;

    const selectedHotelId = () => {
      const id = Number(hotelSelect.value);
      return Number.isSafeInteger(id) && id > 0 ? id : null;
    };

    function renderRoomTypeOptions() {
      const placeholder = api.element(doc, "option", roomTypes.size ? "Chọn loại phòng" : "Xem tồn kho để lấy loại phòng");
      placeholder.value = "";
      const options = [...roomTypes.entries()].sort(([left], [right]) => left - right).map(([id, name]) => {
        const option = api.element(doc, "option", "#" + id + " — " + name);
        option.value = String(id);
        return option;
      });
      const previous = priceSelect.value;
      priceSelect.replaceChildren(placeholder, ...options);
      priceSelect.value = roomTypes.has(Number(previous)) ? previous : "";
    }

    function renderInventoryRow(row) {
      const line = doc.createElement("tr");
      for (const value of [row.roomTypeId, row.name, row.stayDate, row.total, row.sold, row.available]) {
        line.append(api.element(doc, "td", String(value)));
      }
      return line;
    }

    async function loadInventory() {
      const hotelId = selectedHotelId();
      const seq = ++inventorySeq;
      inventoryRows.replaceChildren();
      if (!hotelId) {
        inventoryStatus.textContent = "Chọn khách sạn ở phần Tìm phòng.";
        return;
      }
      const error = api.validateStay(inventoryFrom.value, inventoryTo.value, { allowPast: true });
      if (error) {
        inventoryStatus.textContent = error;
        return;
      }
      inventoryStatus.textContent = "Đang tải tồn kho...";
      const query = "?checkIn=" + encodeURIComponent(inventoryFrom.value) + "&checkOut=" + encodeURIComponent(inventoryTo.value);
      const result = await ctx.client.get("/admin/hotels/" + hotelId + "/inventory" + query);
      if (seq !== inventorySeq) return;
      if (result.kind !== "ok" || !Array.isArray(result.data) || !result.data.every(api.isInventoryRow)) {
        inventoryStatus.textContent = api.describeFailure(result, {
          400: "Khoảng ngày không hợp lệ.",
          403: "Bạn không có quyền xem tồn kho.",
          404: "Khách sạn không tồn tại hoặc không thuộc phạm vi truy cập.",
          invalid: "Dữ liệu tồn kho không hợp lệ."
        });
        return;
      }
      inventoryRows.replaceChildren(...result.data.map(renderInventoryRow));
      for (const row of result.data) roomTypes.set(row.roomTypeId, row.name);
      renderRoomTypeOptions();
      inventoryStatus.textContent = result.data.length === 0
        ? "Không có dòng tồn kho trong khoảng ngày này."
        : "Có " + result.data.length + " dòng tồn kho.";
    }

    function setWriting(value) {
      writing = value;
      createButton.disabled = value;
      priceButton.disabled = value;
    }

    async function createRoomType() {
      if (writing) return;
      const hotelId = selectedHotelId();
      if (!hotelId) {
        createStatus.textContent = "Chọn khách sạn ở phần Tìm phòng.";
        return;
      }
      const name = doc.getElementById("new-room-type-name").value.trim();
      const capacity = doc.getElementById("new-room-type-capacity").value;
      const basePrice = doc.getElementById("new-room-type-price").value;
      const totalRooms = doc.getElementById("new-room-type-total").value;
      const error = api.validateNewRoomType(name, capacity, basePrice, totalRooms);
      if (error) {
        createStatus.textContent = error;
        return;
      }
      setWriting(true);
      createStatus.textContent = "Đang thêm loại phòng...";
      const result = await ctx.client.post("/hotels/" + hotelId + "/room-types", {
        name, capacity: Number(capacity), basePrice: Number(basePrice), totalRooms: Number(totalRooms)
      });
      setWriting(false);
      if (result.status === 201 && api.isRoomType(result.data, ctx.tenantId)
        && result.data.hotelId === hotelId && result.data.name === name) {
        roomTypes.set(result.data.id, result.data.name);
        renderRoomTypeOptions();
        createStatus.textContent = "Đã thêm loại phòng #" + result.data.id + " — " + result.data.name + ".";
        return;
      }
      createStatus.textContent = api.describeFailure(result, {
        400: "Dữ liệu loại phòng không hợp lệ.",
        403: "Bạn không có quyền thêm loại phòng.",
        404: "Khách sạn không tồn tại hoặc không thuộc phạm vi truy cập.",
        invalid: "Response không hợp lệ; chưa thể xác nhận kết quả. Xem tồn kho trước khi thêm lại.",
        network: "Không rõ kết quả do mất kết nối. Xem tồn kho trước khi thêm lại."
      });
    }

    async function updatePrice() {
      if (writing) return;
      const id = Number(priceSelect.value);
      if (!roomTypes.has(id)) {
        priceStatus.textContent = "Chọn loại phòng để sửa giá.";
        return;
      }
      const error = api.validatePrice(priceInput.value);
      if (error) {
        priceStatus.textContent = error;
        return;
      }
      const basePrice = Number(priceInput.value);
      setWriting(true);
      priceStatus.textContent = "Đang sửa giá...";
      const result = await ctx.client.put("/room-types/" + id + "/price", { basePrice });
      setWriting(false);
      if (result.kind === "ok" && api.isRoomType(result.data, ctx.tenantId)
        && result.data.id === id && result.data.basePrice === basePrice) {
        priceStatus.textContent = "Đã sửa giá loại phòng #" + id + " thành " + api.formatMoney(basePrice)
          + ". Đơn đã tạo không đổi số tiền.";
        return;
      }
      priceStatus.textContent = api.describeFailure(result, {
        400: "Giá không hợp lệ.",
        403: "Bạn không có quyền sửa giá.",
        404: "Loại phòng không tồn tại hoặc không thuộc phạm vi truy cập.",
        invalid: "Response không hợp lệ; chưa thể xác nhận giá mới.",
        network: "Không rõ kết quả do mất kết nối; không gửi lại tự động."
      });
    }

    function submitWith(formId, handler) {
      doc.getElementById(formId).addEventListener("submit", async event => {
        event.preventDefault();
        await handler();
      });
    }

    submitWith("inventory-form", loadInventory);
    submitWith("create-room-type-form", createRoomType);
    submitWith("price-form", updatePrice);
    // Loại phòng thuộc từng khách sạn nên đổi khách sạn phải xoá dữ liệu cũ.
    hotelSelect.addEventListener("change", () => {
      inventorySeq += 1;
      roomTypes.clear();
      inventoryRows.replaceChildren();
      inventoryStatus.textContent = "Bấm Xem tồn kho cho khách sạn đã chọn.";
      renderRoomTypeOptions();
    });
    renderRoomTypeOptions();

    return Object.freeze({ loadInventory });
  }

  if (typeof module !== "undefined" && module.exports) module.exports = { initAdmin };
  if (typeof window !== "undefined") window.stayHubAdmin = Object.freeze({ initAdmin });
})();
