const test = require("node:test");
const assert = require("node:assert/strict");
const { validateBooking, validateNewRoom, validatePriceChange, localToUtc, isBookingResponse, initDemoUi } = require("./demo-ui.js");

test("Booking form requires positive room ID and an ordered date-time range", () => {
  assert.match(validateBooking("0", "2026-11-03T14:00", "2026-11-04T10:00"), /Room ID/);
  assert.match(validateBooking("1", "", "2026-11-04T10:00"), /ngày và giờ/);
  assert.match(validateBooking("1", "2026-11-04T10:00", "2026-11-04T10:00"), /sau check-in/);
  assert.match(validateBooking("1", "2026-11-04T10:00", "2026-11-03T14:00"), /sau check-in/);
  assert.equal(validateBooking("1", "2026-11-03T14:00", "2026-11-04T10:00"), null);
});

test("Admin forms reject missing names, bad room IDs and non-positive prices", () => {
  assert.match(validateNewRoom("  ", "100"), /tên phòng/);
  assert.match(validateNewRoom("Room", "-1"), /lớn hơn 0/);
  assert.equal(validateNewRoom("Room", "1500000"), null);
  assert.match(validatePriceChange("0", "100"), /Room ID/);
  assert.match(validatePriceChange("1", "0"), /lớn hơn 0/);
  assert.equal(validatePriceChange("1", "1800000"), null);
});

function fakeUi() {
  const elements = new Map();
  for (const id of [
    "booking-fields", "create-room-fields", "price-fields", "actions-status",
    "booking-form", "create-room-form", "price-form", "book-room-id", "check-in", "check-out",
    "booking-action-status", "new-room-name", "new-room-price", "admin-create-status",
    "price-room-id", "room-price", "admin-price-status", "booking-submit",
    "admin-panel", "create-room-submit", "price-submit"
  ]) {
    elements.set(id, {
      value: "", textContent: "", disabled: true,
      addEventListener(_name, callback) { this.submit = callback; }
    });
  }
  return { doc: { getElementById(id) { return elements.get(id); } }, elements };
}

test("Role from JWT controls Booking and ADMIN UI; no selected room means no POST", async () => {
  for (const [role, bookEnabled, adminEnabled] of [
    [null, false, false], ["USER", true, false], ["ADMIN", false, true]
  ]) {
    const ui = fakeUi();
    const auth = {
      bearerHeaders: () => role ? { Authorization: "Bearer synthetic-test-token" } : {},
      getDisplayRole: () => role,
      getDisplayTenantId: () => 1
    };
    let calls = 0;
    initDemoUi(ui.doc, auth, { getSelectedRoomId: () => null }, async () => { calls++; });
    assert.equal(ui.elements.get("booking-fields").disabled, !bookEnabled);
    assert.equal(ui.elements.get("create-room-fields").disabled, !adminEnabled);
    assert.equal(ui.elements.get("price-fields").disabled, !adminEnabled);
    assert.equal(ui.elements.get("admin-panel").hidden, !adminEnabled);
    if (role === "USER") {
      ui.elements.get("book-room-id").value = "1";
      ui.elements.get("check-in").value = "2026-11-03T14:00";
      ui.elements.get("check-out").value = "2026-11-04T10:00";
      await ui.elements.get("booking-form").submit({ preventDefault() {} });
      assert.match(ui.elements.get("booking-action-status").textContent, /Chọn phòng từ danh sách/);
    }
    if (role === "ADMIN") {
      ui.elements.get("new-room-name").value = "Room";
      ui.elements.get("new-room-price").value = "0";
      await ui.elements.get("create-room-form").submit({ preventDefault() {} });
      assert.match(ui.elements.get("admin-create-status").textContent, /lớn hơn 0/);
    }
    assert.equal(calls, 0);
  }
});

function bookingPage(role = "USER", selectedRoomId = 7) {
  const ui = fakeUi();
  const token = "synthetic-test-token";
  const auth = {
    bearerHeaders: () => role ? { Authorization: "Bearer " + token } : {},
    getDisplayRole: () => role,
    getDisplayTenantId: () => 1
  };
  const rooms = { getSelectedRoomId: () => selectedRoomId };
  ui.elements.get("book-room-id").value = String(selectedRoomId);
  ui.elements.get("check-in").value = "2026-12-01T14:00";
  ui.elements.get("check-out").value = "2026-12-02T11:00";
  return { ...ui, auth, rooms, token, submit: () => ui.elements.get("booking-form").submit({ preventDefault() {} }) };
}

function confirmedBooking(roomId = 7) {
  return { id: 12, tenantId: 1, roomId, userEmail: "user@example.test",
    checkIn: "2026-12-01T07:00:00Z", checkOut: "2026-12-02T04:00:00Z",
    status: "CONFIRMED", createdAt: "2026-11-03T02:15:30Z" };
}

test("Local datetime converts to timezone-aware UTC and rejects invalid calendar values", () => {
  assert.match(localToUtc("2026-12-01T14:00"), /Z$/);
  for (const value of ["1999-12-01T14:00", "2026-02-30T14:00", "2026-12-01T25:00", "2026-12-01T14:00Z", "bad"]) {
    assert.equal(localToUtc(value), null);
  }
  assert.match(validateBooking("7", "2026-02-30T14:00", "2026-12-02T11:00"), /không hợp lệ/);
});

test("POST uses selected room, exact contract body, Bearer header and UTC times", async () => {
  const ui = bookingPage();
  let calls = 0;
  initDemoUi(ui.doc, ui.auth, ui.rooms, async (url, options) => {
    calls++;
    assert.equal(url, "http://localhost:8080/api/bookings");
    assert.equal(options.method, "POST");
    assert.deepEqual(options.headers, { Authorization: "Bearer synthetic-test-token", "Content-Type": "application/json" });
    assert.deepEqual(JSON.parse(options.body), {
      roomId: 7,
      checkIn: localToUtc("2026-12-01T14:00"),
      checkOut: localToUtc("2026-12-02T11:00")
    });
    return { status: 201, json: async () => confirmedBooking() };
  });
  await ui.submit();
  assert.equal(calls, 1);
  assert.match(ui.elements.get("booking-action-status").textContent, /Booking #12.*CONFIRMED/);
  assert.doesNotMatch(ui.elements.get("booking-action-status").textContent, /synthetic-test-token/);
});

test("Datetime conversion respects a non-UTC browser timezone", () => {
  const original = process.env.TZ;
  try {
    process.env.TZ = "Asia/Ho_Chi_Minh";
    assert.equal(localToUtc("2026-12-01T14:00"), "2026-12-01T07:00:00.000Z");
    assert.equal(localToUtc("2026-12-02T11:00"), "2026-12-02T04:00:00.000Z");
  } finally {
    if (original === undefined) delete process.env.TZ;
    else process.env.TZ = original;
  }
});

test("No JWT, ADMIN and unselected or mismatched room never POST", async () => {
  for (const [role, selected, input] of [[null, 7, "7"], ["ADMIN", 7, "7"], ["USER", null, ""], ["USER", 7, "8"]]) {
    const ui = bookingPage(role, selected);
    ui.elements.get("book-room-id").value = input;
    let calls = 0;
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; });
    await ui.submit();
    assert.equal(calls, 0);
    assert.doesNotMatch(ui.elements.get("booking-action-status").textContent, /thành công/);
  }
});

test("Invalid time and malformed 201 response cannot be presented as confirmed", async () => {
  const ui = bookingPage();
  let calls = 0;
  initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; return { status: 201, json: async () => ({ ...confirmedBooking(), status: "PENDING" }) }; });
  ui.elements.get("check-out").value = "2026-12-01T14:00";
  await ui.submit();
  assert.equal(calls, 0);
  assert.match(ui.elements.get("booking-action-status").textContent, /sau check-in/);
  ui.elements.get("check-out").value = "2026-12-02T11:00";
  await ui.submit();
  assert.equal(calls, 1);
  assert.match(ui.elements.get("booking-action-status").textContent, /Response Booking không hợp lệ/);
  assert.equal(isBookingResponse({ ...confirmedBooking(), tenantId: 2 }, 7, 1), false);
  assert.equal(isBookingResponse({ ...confirmedBooking(), id: '<img src=x onerror="alert(1)">' }, 7, 1), false);
  assert.doesNotMatch(ui.elements.get("booking-action-status").textContent, /<img|synthetic-test-token/);
  assert.equal(isBookingResponse(confirmedBooking(), 7, 1), true);
});

test("Booking HTTP errors and network failure produce safe distinct messages without retry", async () => {
  const cases = [[400, /Dữ liệu.*không hợp lệ/], [401, /hết hạn/], [403, /không có quyền/],
    [404, /không tồn tại/], [409, /đặt trong khoảng/], [500, /lỗi máy chủ/]];
  for (const [code, message] of cases) {
    const ui = bookingPage();
    let calls = 0;
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; return { status: code, json: async () => { throw new Error("secret SQL"); } }; });
    await ui.submit();
    assert.equal(calls, 1);
    assert.match(ui.elements.get("booking-action-status").textContent, message);
    assert.doesNotMatch(ui.elements.get("booking-action-status").textContent, /secret SQL|synthetic-test-token/);
  }
  const ui = bookingPage();
  let calls = 0;
  initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; throw new Error("secret network error"); });
  await ui.submit();
  assert.equal(calls, 1);
  assert.match(ui.elements.get("booking-action-status").textContent, /Không kết nối/);
  assert.doesNotMatch(ui.elements.get("booking-action-status").textContent, /secret/);
});

test("Pending Booking blocks duplicate submit and clears loading after completion", async () => {
  const ui = bookingPage();
  let resolveFetch;
  let calls = 0;
  initDemoUi(ui.doc, ui.auth, ui.rooms, () => { calls++; return new Promise(resolve => { resolveFetch = resolve; }); });
  const first = ui.submit();
  assert.equal(ui.elements.get("booking-submit").disabled, true);
  assert.match(ui.elements.get("booking-action-status").textContent, /Đang gửi/);
  await ui.submit();
  assert.equal(calls, 1);
  resolveFetch({ status: 201, json: async () => confirmedBooking() });
  await first;
  assert.equal(ui.elements.get("booking-submit").disabled, false);
});

function adminPage(role = "ADMIN", selectedPriceId = 8) {
  const ui = fakeUi();
  const existing = { id: 8, tenantId: 1, name: "Room 8", price: 500000 };
  const records = [existing];
  const auth = {
    bearerHeaders: () => role ? { Authorization: "Bearer synthetic-test-token" } : {},
    getDisplayRole: () => role,
    getDisplayTenantId: () => 1
  };
  const rooms = {
    getSelectedRoomId: () => null,
    getSelectedPriceRoomId: () => selectedPriceId,
    getLoadedRoomById: id => records.find(room => room.id === id) || null,
    applyRoom: (room, tenantId) => {
      if (room.tenantId !== tenantId || !Number.isSafeInteger(room.id) || room.id <= 0
        || typeof room.name !== "string" || !room.name.trim() || room.name.trim().length > 100
        || typeof room.price !== "number" || room.price <= 0 || room.price > 99999999.99) return false;
      const index = records.findIndex(existingRoom => existingRoom.id === room.id);
      if (index < 0) records.push(room); else records[index] = room;
      return true;
    }
  };
  ui.elements.get("new-room-name").value = "  Suite Room 301  ";
  ui.elements.get("new-room-price").value = "2500000.50";
  ui.elements.get("price-room-id").value = String(selectedPriceId);
  ui.elements.get("room-price").value = "2000000";
  return { ...ui, auth, rooms, records,
    create: () => ui.elements.get("create-room-form").submit({ preventDefault() {} }),
    changePrice: () => ui.elements.get("price-form").submit({ preventDefault() {} }) };
}

test("ADMIN creates Room with exact POST contract and adds valid response to list", async () => {
  const ui = adminPage();
  let calls = 0;
  initDemoUi(ui.doc, ui.auth, ui.rooms, async (url, options) => {
    calls++;
    assert.equal(url, "http://localhost:8080/api/rooms");
    assert.equal(options.method, "POST");
    assert.deepEqual(options.headers, { Authorization: "Bearer synthetic-test-token", "Content-Type": "application/json" });
    assert.deepEqual(JSON.parse(options.body), { name: "Suite Room 301", price: 2500000.5 });
    return { status: 201, json: async () => ({ id: 14, tenantId: 1, name: "Suite Room 301", price: 2500000.5 }) };
  });
  await ui.create();
  assert.equal(calls, 1);
  assert.equal(ui.records.length, 2);
  assert.match(ui.elements.get("admin-create-status").textContent, /Đã thêm phòng #14/);
  assert.doesNotMatch(ui.elements.get("admin-create-status").textContent, /synthetic-test-token/);
});

test("ADMIN changes price of selected loaded Room with exact PUT contract", async () => {
  const ui = adminPage();
  let calls = 0;
  initDemoUi(ui.doc, ui.auth, ui.rooms, async (url, options) => {
    calls++;
    assert.equal(url, "http://localhost:8080/api/rooms/8/price");
    assert.equal(options.method, "PUT");
    assert.deepEqual(options.headers, { Authorization: "Bearer synthetic-test-token", "Content-Type": "application/json" });
    assert.deepEqual(JSON.parse(options.body), { price: 2000000 });
    return { status: 200, json: async () => ({ id: 8, tenantId: 1, name: "Room 8", price: 2000000 }) };
  });
  await ui.changePrice();
  assert.equal(calls, 1);
  assert.equal(ui.records[0].price, 2000000);
  assert.match(ui.elements.get("admin-price-status").textContent, /Đã sửa giá phòng #8/);
});

test("USER and signed-out UI cannot send POST or PUT Rooms", async () => {
  for (const role of ["USER", null]) {
    const ui = adminPage(role);
    let calls = 0;
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; });
    assert.equal(ui.elements.get("admin-panel").hidden, true);
    assert.equal(ui.elements.get("create-room-fields").disabled, true);
    assert.equal(ui.elements.get("price-fields").disabled, true);
    await ui.create();
    await ui.changePrice();
    assert.equal(calls, 0);
  }
});

test("Room name and price validation follows Contract v2 limits", async () => {
  for (const name of [" ", "X".repeat(101)]) {
    const ui = adminPage();
    ui.elements.get("new-room-name").value = name;
    let calls = 0;
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; });
    await ui.create();
    assert.equal(calls, 0);
    assert.match(ui.elements.get("admin-create-status").textContent, /tên phòng|Tên phòng/);
  }
  for (const price of ["-1", "0", "100000000", "99999999.999", "1.234", "1e3"]) {
    const ui = adminPage();
    ui.elements.get("new-room-price").value = price;
    ui.elements.get("room-price").value = price;
    let calls = 0;
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; });
    await ui.create();
    await ui.changePrice();
    assert.equal(calls, 0);
    assert.match(ui.elements.get("admin-create-status").textContent, /Giá phòng/);
    assert.match(ui.elements.get("admin-price-status").textContent, /Giá mới/);
  }
  assert.equal(validateNewRoom("R".repeat(100), "99999999.99"), null);
  assert.equal(validatePriceChange("8", "0.01"), null);
});

test("PUT requires an ID selected from the loaded list", async () => {
  for (const [selected, input, loaded] of [[null, "", true], [8, "9", true], [8, "8", false]]) {
    const ui = adminPage("ADMIN", selected);
    ui.elements.get("price-room-id").value = input;
    if (!loaded) ui.rooms.getLoadedRoomById = () => null;
    let calls = 0;
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; });
    await ui.changePrice();
    assert.equal(calls, 0);
    assert.match(ui.elements.get("admin-price-status").textContent, /Chọn phòng từ danh sách/);
  }
});

test("Malformed success response never updates Room list or displays untrusted data", async () => {
  for (const bad of [
    { id: 14, tenantId: 2, name: "Other tenant", price: 100 },
    { id: '<img src=x onerror="alert(1)">', tenantId: 1, name: "X", price: 100 },
    { id: 8, tenantId: 1, name: "Room 8", price: 0 }
  ]) {
    const ui = adminPage();
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => ({ status: 201, json: async () => bad }));
    await ui.create();
    assert.equal(ui.records.length, 1);
    assert.match(ui.elements.get("admin-create-status").textContent, /Response Room không hợp lệ/);
    assert.doesNotMatch(ui.elements.get("admin-create-status").textContent, /<img|Other tenant/);
  }
  const ui = adminPage();
  initDemoUi(ui.doc, ui.auth, ui.rooms, async () => ({ status: 200, json: async () => ({ id: 9, tenantId: 1, name: "Room 8", price: 2000000 }) }));
  await ui.changePrice();
  assert.equal(ui.records[0].price, 500000);
  assert.match(ui.elements.get("admin-price-status").textContent, /Response Room không hợp lệ/);
});

test("ADMIN HTTP errors and network failures are safe and do not retry", async () => {
  for (const method of ["create", "changePrice"]) {
    for (const [code, message] of [[400, /Dữ liệu/], [401, /hết hạn/], [403, /không có quyền/],
      [404, /không tồn tại/], [500, /lỗi máy chủ/]]) {
      const ui = adminPage();
      let calls = 0;
      initDemoUi(ui.doc, ui.auth, ui.rooms, async () => {
        calls++;
        return { status: code, json: async () => { throw new Error("secret ProblemDetail"); } };
      });
      await ui[method]();
      const status = ui.elements.get(method === "create" ? "admin-create-status" : "admin-price-status").textContent;
      assert.equal(calls, 1);
      assert.match(status, message);
      assert.doesNotMatch(status, /secret|synthetic-test-token/);
      assert.equal(ui.records.length, 1);
      assert.equal(ui.records[0].price, 500000);
    }
    const ui = adminPage();
    let calls = 0;
    initDemoUi(ui.doc, ui.auth, ui.rooms, async () => { calls++; throw new Error("secret network failure"); });
    await ui[method]();
    assert.equal(calls, 1);
    const status = ui.elements.get(method === "create" ? "admin-create-status" : "admin-price-status").textContent;
    assert.match(status, /Không kết nối/);
    assert.doesNotMatch(status, /secret/);
  }
});

test("ADMIN loading blocks duplicate and concurrent POST/PUT requests", async () => {
  const ui = adminPage();
  let resolveFetch;
  let calls = 0;
  initDemoUi(ui.doc, ui.auth, ui.rooms, () => { calls++; return new Promise(resolve => { resolveFetch = resolve; }); });
  const first = ui.create();
  assert.equal(ui.elements.get("create-room-submit").disabled, true);
  assert.equal(ui.elements.get("price-submit").disabled, true);
  assert.match(ui.elements.get("admin-create-status").textContent, /Đang thêm/);
  await ui.create();
  await ui.changePrice();
  assert.equal(calls, 1);
  resolveFetch({ status: 201, json: async () => ({ id: 14, tenantId: 1, name: "Suite Room 301", price: 2500000.5 }) });
  await first;
  assert.equal(ui.elements.get("create-room-submit").disabled, false);
  assert.equal(ui.elements.get("price-submit").disabled, false);
});
