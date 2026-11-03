const test = require("node:test");
const assert = require("node:assert/strict");
const { loadRooms, getSelectedRoomId, getSelectedPriceRoomId, getLoadedRoomById, applyRoom } = require("./rooms.js");

function page() {
  const status = { textContent: "" };
  const list = {
    children: [],
    replaceChildren() { this.children = []; },
    appendChild(child) { this.children.push(child); }
  };
  const selectedInput = { value: "" };
  const priceInput = { value: "" };
  const doc = {
    getElementById(id) {
      return id === "rooms-status" ? status : id === "room-list" ? list
        : id === "book-room-id" ? selectedInput : id === "price-room-id" ? priceInput : null;
    },
    createElement(tag) {
      assert.ok(["li", "button"].includes(tag));
      return { textContent: "", children: [], appendChild(child) { this.children.push(child); },
        addEventListener(_name, callback) { this.click = callback; } };
    }
  };
  return { doc, status, list, selectedInput, priceInput };
}

const signedIn = {
  bearerHeaders: () => ({ Authorization: "Bearer synthetic-test-token" }),
  getDisplayTenantId: () => 1
};
const signedOut = { bearerHeaders: () => ({}) };
const admin = { ...signedIn, getDisplayRole: () => "ADMIN" };

test("No JWT means no API request", async () => {
  const ui = page();
  let calls = 0;
  await loadRooms(ui.doc, signedOut, async () => { calls++; throw new Error("must not fetch"); });
  assert.equal(calls, 0);
  assert.match(ui.status.textContent, /Đăng nhập/);
  assert.equal(ui.list.children.length, 0);
});

test("JWT without a numeric tenant does not call Rooms API", async () => {
  const ui = page();
  let calls = 0;
  await loadRooms(ui.doc, { bearerHeaders: signedIn.bearerHeaders }, async () => { calls++; });
  assert.equal(calls, 0);
  assert.match(ui.status.textContent, /tenant hợp lệ/);
  assert.equal(ui.list.children.length, 0);
});

test("200 displays id, name and price with Bearer JWT without inferring availability", async () => {
  const ui = page();
  let resolveFetch;
  const fetchRooms = (url, options) => {
    assert.equal(url, "http://localhost:8080/api/rooms");
    assert.equal(options.method, "GET");
    assert.deepEqual(options.headers, { Authorization: "Bearer synthetic-test-token", Accept: "application/json" });
    return new Promise(resolve => { resolveFetch = resolve; });
  };
  const pending = loadRooms(ui.doc, signedIn, fetchRooms);
  assert.match(ui.status.textContent, /Đang tải/);
  resolveFetch({ status: 200, ok: true, json: async () => [
    { id: 1, tenantId: 1, name: "Room A-101", price: 1500000 },
    { id: 2, tenantId: 1, name: "Room A-102", price: 1800000, available: false }
  ] });
  await pending;
  assert.equal(ui.list.children.length, 2);
  assert.match(ui.list.children[0].textContent, /#1.*Room A-101.*1\.500\.000 VND/);
  assert.match(ui.list.children[1].textContent, /#2.*Room A-102.*1\.800\.000 VND/);
  assert.doesNotMatch(ui.list.children[0].textContent + ui.list.children[1].textContent, /Còn trống|Đã đặt/);
  assert.match(ui.status.textContent, /Đã tải 2 phòng/);
});

test("Booking room ID comes from selecting a rendered room and clears on reload", async () => {
  const ui = page();
  const rooms = [{ id: 7, tenantId: 1, name: "Room 7", price: 500000 }];
  await loadRooms(ui.doc, signedIn, async () => ({ status: 200, ok: true, json: async () => rooms }));
  assert.equal(getSelectedRoomId(ui.doc), null);
  ui.list.children[0].children[0].click();
  assert.equal(getSelectedRoomId(ui.doc), 7);
  assert.equal(ui.selectedInput.value, "7");
  await loadRooms(ui.doc, signedIn, async () => ({ status: 200, ok: true, json: async () => [] }));
  assert.equal(getSelectedRoomId(ui.doc), null);
  assert.equal(ui.selectedInput.value, "");
});

test("ADMIN price selection uses a loaded Room ID and clears on reload", async () => {
  const ui = page();
  await loadRooms(ui.doc, admin, async () => ({ status: 200, ok: true,
    json: async () => [{ id: 8, tenantId: 1, name: "Room 8", price: 500000 }] }));
  assert.equal(ui.list.children[0].children.length, 2);
  assert.equal(getSelectedPriceRoomId(ui.doc), null);
  ui.list.children[0].children[1].click();
  assert.equal(getSelectedPriceRoomId(ui.doc), 8);
  assert.equal(ui.priceInput.value, "8");
  assert.equal(getLoadedRoomById(ui.doc, 8).price, 500000);
  await loadRooms(ui.doc, admin, async () => ({ status: 200, ok: true, json: async () => [] }));
  assert.equal(getSelectedPriceRoomId(ui.doc), null);
  assert.equal(ui.priceInput.value, "");
});

test("A valid ADMIN response updates the visible list without crossing tenant", async () => {
  const ui = page();
  await loadRooms(ui.doc, admin, async () => ({ status: 200, ok: true,
    json: async () => [{ id: 8, tenantId: 1, name: "Room 8", price: 500000 }] }));
  assert.equal(applyRoom(ui.doc, { id: 8, tenantId: 1, name: "Room 8", price: 2000000 }, 1), true);
  assert.equal(getLoadedRoomById(ui.doc, 8).price, 2000000);
  assert.match(ui.list.children[0].textContent, /2\.000\.000 VND/);
  assert.equal(applyRoom(ui.doc, { id: 14, tenantId: 1, name: "Suite", price: 2500000 }, 1), true);
  assert.equal(ui.list.children.length, 2);
  assert.equal(getLoadedRoomById(ui.doc, 14).name, "Suite");
  assert.equal(applyRoom(ui.doc, { id: 20, tenantId: 2, name: "Other", price: 100 }, 1), false);
  assert.equal(getLoadedRoomById(ui.doc, 20), null);
  assert.equal(ui.list.children.length, 2);
});

test("USER Rooms list does not offer an ADMIN price action", async () => {
  const ui = page();
  await loadRooms(ui.doc, signedIn, async () => ({ status: 200, ok: true,
    json: async () => [{ id: 8, tenantId: 1, name: "Room 8", price: 500000 }] }));
  assert.equal(ui.list.children[0].children.length, 1);
  assert.equal(getSelectedPriceRoomId(ui.doc), null);
});

test("200 with no rooms displays the empty state", async () => {
  const ui = page();
  await loadRooms(ui.doc, signedIn, async () => ({ status: 200, ok: true, json: async () => [] }));
  assert.equal(ui.list.children.length, 0);
  assert.match(ui.status.textContent, /chưa có phòng/);
});

test("401 and 403 show distinct errors without rendering rooms", async () => {
  for (const [code, message] of [[401, /hết hạn/], [403, /không có quyền/]]) {
    const ui = page();
    await loadRooms(ui.doc, signedIn, async () => ({ status: code, ok: false }));
    assert.match(ui.status.textContent, message);
    assert.equal(ui.list.children.length, 0);
  }
});

test("Network failure reports unavailable Gateway", async () => {
  const ui = page();
  await loadRooms(ui.doc, signedIn, async () => { throw new Error("connection refused"); });
  assert.match(ui.status.textContent, /Không kết nối được Gateway/);
  assert.equal(ui.list.children.length, 0);
});

test("Unexpected Room JSON is not presented as valid data", async () => {
  const ui = page();
  await loadRooms(ui.doc, signedIn, async () => ({
    status: 200, ok: true, json: async () => [{ id: 1, tenantId: 1, price: 1500000 }]
  }));
  assert.match(ui.status.textContent, /không hợp lệ/);
  assert.equal(ui.list.children.length, 0);
});

test("Non-positive room ID or price is rejected without rendering", async () => {
  for (const room of [
    { id: 0, tenantId: 1, name: "Room", price: 100 },
    { id: 1, tenantId: 1, name: "Room", price: -1 }
  ]) {
    const ui = page();
    await loadRooms(ui.doc, signedIn, async () => ({
      status: 200, ok: true, json: async () => [room]
    }));
    assert.match(ui.status.textContent, /không hợp lệ/);
    assert.equal(ui.list.children.length, 0);
  }
});

test("Room fields must match Contract v2 types and limits", async () => {
  const valid = { id: 1, tenantId: 1, name: "Standard Room 101", price: 500000.00 };
  for (const room of [
    { ...valid, id: 1.5 },
    { ...valid, tenantId: "1" },
    { ...valid, tenantId: 0 },
    { ...valid, tenantId: -1 },
    { ...valid, tenantId: 1.5 },
    { ...valid, name: "   " },
    { ...valid, name: "X".repeat(101) },
    { ...valid, price: 0 },
    { ...valid, price: 99999999.999 },
    { ...valid, price: 100000000 },
    { ...valid, price: 1.234 }
  ]) {
    const ui = page();
    await loadRooms(ui.doc, signedIn, async () => ({
      status: 200, ok: true, json: async () => [room]
    }));
    assert.match(ui.status.textContent, /không hợp lệ/);
    assert.equal(ui.list.children.length, 0);
  }
});

test("Room name and price at contract limits remain displayable", async () => {
  const ui = page();
  await loadRooms(ui.doc, signedIn, async () => ({
    status: 200, ok: true,
    json: async () => [{ id: 1, tenantId: 1, name: "R".repeat(100), price: 99999999.99 }]
  }));
  assert.equal(ui.list.children.length, 1);
  assert.match(ui.list.children[0].textContent, /99\.999\.999,99 VND/);
});

test("Any cross-tenant room blocks the whole list from rendering", async () => {
  const ui = page();
  await loadRooms(ui.doc, signedIn, async () => ({
    status: 200, ok: true,
    json: async () => [
      { id: 1, tenantId: 1, name: "Own room", price: 500000 },
      { id: 2, tenantId: 2, name: "Other tenant room", price: 500000 }
    ]
  }));
  assert.equal(ui.list.children.length, 0);
  assert.match(ui.status.textContent, /không khớp tenant/);
  assert.doesNotMatch(ui.status.textContent, /Other tenant room/);
});

test("Untrusted room name is rendered as text, not HTML", async () => {
  const ui = page();
  ui.doc.createElement = tag => {
    assert.ok(["li", "button"].includes(tag));
    return {
      textContent: "",
      children: [],
      appendChild(child) { this.children.push(child); },
      addEventListener(_name, callback) { this.click = callback; },
      set innerHTML(_value) { throw new Error("HTML sink used for room data"); }
    };
  };
  const name = '<img src=x onerror="alert(1)">';
  await loadRooms(ui.doc, signedIn, async () => ({
    status: 200, ok: true,
    json: async () => [{ id: 1, tenantId: 1, name, price: 100 }]
  }));
  assert.equal(ui.list.children.length, 1);
  assert.match(ui.list.children[0].textContent, /<img src=x onerror="alert\(1\)">/);
  assert.equal(applyRoom(ui.doc, { id: 2, tenantId: 1, name, price: 200 }, 1), true);
  assert.equal(ui.list.children.length, 2);
  assert.match(ui.list.children[1].textContent, /<img src=x onerror="alert\(1\)">/);
});

test("Malformed JSON has a distinct error from network failure", async () => {
  const ui = page();
  await loadRooms(ui.doc, signedIn, async () => ({
    status: 200, ok: true, json: async () => { throw new SyntaxError("bad JSON"); }
  }));
  assert.match(ui.status.textContent, /JSON hợp lệ/);
});
