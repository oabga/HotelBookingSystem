(function () {
  "use strict";

  const roomsUrl = "http://localhost:8080/api/rooms";
  const priceFormatter = new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 });
  const selectedByDocument = new WeakMap();
  const selectedPriceByDocument = new WeakMap();
  const loadedByDocument = new WeakMap();

  function getSelectedRoomId(doc) {
    return selectedByDocument.get(doc) || null;
  }

  function getSelectedPriceRoomId(doc) {
    return selectedPriceByDocument.get(doc) || null;
  }

  function getLoadedRoomById(doc, id) {
    return (loadedByDocument.get(doc) || []).find(room => room.id === id) || null;
  }

  function applyRoom(doc, room, tenantId) {
    if (!isRoomList([room]) || room.tenantId !== tenantId) return false;
    const current = loadedByDocument.get(doc) || [];
    const updated = current.filter(existing => existing.id !== room.id);
    updated.push(room);
    updated.sort((left, right) => left.id - right.id);
    loadedByDocument.set(doc, updated);
    renderRooms(doc, updated, true);
    doc.getElementById("rooms-status").textContent = "Đã tải " + updated.length + " phòng.";
    return true;
  }

  function clearSelection(doc) {
    selectedByDocument.delete(doc);
    selectedPriceByDocument.delete(doc);
    const input = doc.getElementById("book-room-id");
    if (input) input.value = "";
    const priceInput = doc.getElementById("price-room-id");
    if (priceInput) priceInput.value = "";
  }

  function renderRooms(doc, rooms, showAdmin = false) {
    const list = doc.getElementById("room-list");
    list.replaceChildren();
    for (const room of rooms) {
      const item = doc.createElement("li");
      item.textContent = "#" + room.id + " — " + room.name + " — "
        + priceFormatter.format(room.price) + " VND";
      const input = doc.getElementById("book-room-id");
      if (input) {
        const button = doc.createElement("button");
        button.type = "button";
        button.textContent = "Chọn phòng #" + room.id;
        button.addEventListener("click", () => {
          selectedByDocument.set(doc, room.id);
          input.value = String(room.id);
        });
        item.appendChild(button);
      }
      const priceInput = doc.getElementById("price-room-id");
      if (showAdmin && priceInput) {
        const button = doc.createElement("button");
        button.type = "button";
        button.textContent = "Sửa giá phòng #" + room.id;
        button.addEventListener("click", () => {
          selectedPriceByDocument.set(doc, room.id);
          priceInput.value = String(room.id);
        });
        item.appendChild(button);
      }
      list.appendChild(item);
    }
  }

  function isRoomList(value) {
    return Array.isArray(value) && value.every(room => room && Number.isSafeInteger(room.id)
      && room.id > 0 && Number.isSafeInteger(room.tenantId) && room.tenantId > 0
      && typeof room.name === "string" && room.name.trim().length >= 1 && room.name.trim().length <= 100
      && typeof room.price === "number" && Number.isFinite(room.price)
      && room.price > 0 && room.price <= 99999999.99
      && /^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(String(room.price)));
  }

  async function loadRooms(doc, auth, fetchRooms) {
    const status = doc.getElementById("rooms-status");
    const list = doc.getElementById("room-list");
    const headers = auth && typeof auth.bearerHeaders === "function" ? auth.bearerHeaders() : {};
    clearSelection(doc);
    loadedByDocument.delete(doc);

    if (!headers.Authorization) {
      list.replaceChildren();
      status.textContent = "Đăng nhập để xem phòng.";
      return;
    }

    const tenantId = auth && typeof auth.getDisplayTenantId === "function"
      ? auth.getDisplayTenantId() : null;
    if (!Number.isSafeInteger(tenantId) || tenantId <= 0) {
      list.replaceChildren();
      status.textContent = "Token không có tenant hợp lệ. Hãy đăng nhập lại.";
      return;
    }

    list.replaceChildren();
    status.textContent = "Đang tải danh sách phòng...";
    try {
      const response = await fetchRooms(roomsUrl, {
        method: "GET",
        headers: { Authorization: headers.Authorization, Accept: "application/json" }
      });
      if (response.status === 401) {
        status.textContent = "Phiên không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại.";
        return;
      }
      if (response.status === 403) {
        status.textContent = "Bạn không có quyền xem danh sách phòng.";
        return;
      }
      if (!response.ok) {
        status.textContent = "Không tải được phòng (HTTP " + response.status + ").";
        return;
      }

      let rooms;
      try {
        rooms = await response.json();
      } catch (_error) {
        status.textContent = "Response phòng không phải JSON hợp lệ.";
        return;
      }
      if (!isRoomList(rooms)) {
        status.textContent = "Dữ liệu phòng không hợp lệ.";
        return;
      }
      if (rooms.some(room => room.tenantId !== tenantId)) {
        status.textContent = "Dữ liệu phòng không khớp tenant trong JWT. Không hiển thị danh sách.";
        return;
      }
      loadedByDocument.set(doc, rooms);
      renderRooms(doc, rooms, auth && typeof auth.getDisplayRole === "function" && auth.getDisplayRole() === "ADMIN");
      status.textContent = rooms.length === 0
        ? "Tenant này chưa có phòng."
        : "Đã tải " + rooms.length + " phòng.";
    } catch (_error) {
      status.textContent = "Không kết nối được Gateway. Kiểm tra backend hoặc CORS.";
    }
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { loadRooms, renderRooms, isRoomList, getSelectedRoomId, getSelectedPriceRoomId, getLoadedRoomById, applyRoom };
  }
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    window.stayHubRooms = Object.freeze({
      reload: () => loadRooms(document, window.stayHubAuth, window.fetch.bind(window)),
      getSelectedRoomId: () => getSelectedRoomId(document),
      getSelectedPriceRoomId: () => getSelectedPriceRoomId(document),
      getLoadedRoomById: id => getLoadedRoomById(document, id),
      applyRoom: (room, tenantId) => applyRoom(document, room, tenantId)
    });
    window.stayHubRooms.reload();
  }
})();
