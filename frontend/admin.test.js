const test = require("node:test");
const assert = require("node:assert/strict");
const api = require("./api.js");
const { initAdmin } = require("./admin.js");
const { FakeDocument, response, fakeFetch, fakeAuth } = require("./test-support.js");

const INVENTORY = "GET /api/admin/hotels/1/inventory";
const rows = [
  { roomTypeId: 1, name: "Standard", stayDate: "2026-11-24", total: 3, sold: 1, available: 2 },
  { roomTypeId: 1, name: "Standard", stayDate: "2026-11-25", total: 3, sold: 2, available: 1 },
  { roomTypeId: 2, name: "Deluxe", stayDate: "2026-11-24", total: 4, sold: 4, available: 0 }
];
const roomType = { id: 7, tenantId: 1, hotelId: 1, name: "Suite", capacity: 4, basePrice: 2500000, totalRooms: 2 };

function setup(routes = {}) {
  const doc = new FakeDocument();
  const fetchFn = fakeFetch({ [INVENTORY]: response(200, rows), ...routes });
  const client = api.createClient({ fetchFn, auth: fakeAuth("ADMIN") });
  initAdmin(doc, { client, role: "ADMIN", tenantId: 1 });
  const el = id => doc.getElementById(id);
  el("hotel-select").value = "1";
  const loadInventory = async () => {
    el("inventory-check-in").value = "2026-11-24";
    el("inventory-check-out").value = "2026-11-26";
    await el("inventory-form").fire("submit");
  };
  const fillRoomType = () => {
    el("new-room-type-name").value = " Suite ";
    el("new-room-type-capacity").value = "4";
    el("new-room-type-price").value = "2500000";
    el("new-room-type-total").value = "2";
  };
  return { el, fetchFn, loadInventory, fillRoomType };
}

test("Inventory uses the selected hotel and renders each night, including sold-out", async () => {
  const { el, fetchFn, loadInventory } = setup();
  await loadInventory();
  assert.equal(fetchFn.calls[0].url, "http://localhost:8080/api/admin/hotels/1/inventory?checkIn=2026-11-24&checkOut=2026-11-26");
  assert.equal(el("inventory-rows").children.length, 3);
  assert.ok(el("inventory-rows").children[2].textContent.includes("Deluxe"));
});

test("Inventory may look at past dates but needs a hotel", async () => {
  const { el, fetchFn } = setup();
  el("inventory-check-in").value = "2020-01-01";
  el("inventory-check-out").value = "2020-01-03";
  await el("inventory-form").fire("submit");
  assert.equal(fetchFn.called(INVENTORY).length, 1);
  el("hotel-select").value = "";
  await el("inventory-form").fire("submit");
  assert.match(el("inventory-status").textContent, /Chọn khách sạn/);
  assert.equal(fetchFn.called(INVENTORY).length, 1);
});

test("Inventory reports each error status", async () => {
  for (const [status, pattern] of [[400, /Khoảng ngày/], [401, /hết hạn/], [403, /không có quyền/], [404, /không tồn tại/]]) {
    const { el, loadInventory } = setup({ [INVENTORY]: response(status) });
    await loadInventory();
    assert.match(el("inventory-status").textContent, pattern);
  }
});

test("Price choices come from inventory, once per room type", async () => {
  const { el, loadInventory } = setup();
  await loadInventory();
  const options = el("price-room-type").children;
  assert.deepEqual(options.map(option => option.value), ["", "1", "2"]);
});

test("Changing hotel clears room types of the previous hotel", async () => {
  const { el, loadInventory } = setup();
  await loadInventory();
  await el("hotel-select").fire("change");
  assert.equal(el("inventory-rows").children.length, 0);
  assert.deepEqual(el("price-room-type").children.map(option => option.value), [""]);
});

test("Create room type sends only contract fields as numbers", async () => {
  const { el, fetchFn, fillRoomType } = setup({ "POST /api/hotels/1/room-types": response(201, roomType) });
  fillRoomType();
  await el("create-room-type-form").fire("submit");
  assert.deepEqual(fetchFn.called("POST /api/hotels/1/room-types")[0].body, {
    name: "Suite", capacity: 4, basePrice: 2500000, totalRooms: 2
  });
  assert.match(el("admin-create-status").textContent, /Đã thêm loại phòng #7/);
  assert.ok(el("price-room-type").children.some(option => option.value === "7"), "new type can be repriced");
});

test("Invalid room type never reaches the API", async () => {
  const { el, fetchFn, fillRoomType } = setup();
  fillRoomType();
  el("new-room-type-capacity").value = "21";
  await el("create-room-type-form").fire("submit");
  assert.match(el("admin-create-status").textContent, /Số khách/);
  assert.equal(fetchFn.called("POST /api/hotels/1/room-types").length, 0);
});

test("Create room type rejects a response for another tenant and reports errors", async () => {
  const cross = setup({ "POST /api/hotels/1/room-types": response(201, { ...roomType, tenantId: 2 }) });
  cross.fillRoomType();
  await cross.el("create-room-type-form").fire("submit");
  assert.match(cross.el("admin-create-status").textContent, /chưa thể xác nhận/);
  for (const [status, pattern] of [[400, /không hợp lệ/], [403, /không có quyền/], [404, /không tồn tại/]]) {
    const { el, fillRoomType } = setup({ "POST /api/hotels/1/room-types": response(status) });
    fillRoomType();
    await el("create-room-type-form").fire("submit");
    assert.match(el("admin-create-status").textContent, pattern);
  }
});

test("Update price sends basePrice to the room type route", async () => {
  const { el, fetchFn, loadInventory } = setup({
    "PUT /api/room-types/1/price": response(200, { ...roomType, id: 1, name: "Standard", basePrice: 750000.5 })
  });
  await loadInventory();
  el("price-room-type").value = "1";
  el("new-price").value = "750000.50";
  await el("price-form").fire("submit");
  assert.deepEqual(fetchFn.called("PUT /api/room-types/1/price")[0].body, { basePrice: 750000.5 });
  assert.match(el("admin-price-status").textContent, /Đơn đã tạo không đổi/);
});

test("Update price needs a loaded room type and a valid price, and reports errors", async () => {
  const { el, fetchFn, loadInventory } = setup();
  el("price-room-type").value = "1";
  el("new-price").value = "1";
  await el("price-form").fire("submit");
  assert.match(el("admin-price-status").textContent, /Chọn loại phòng/);
  await loadInventory();
  el("price-room-type").value = "1";
  el("new-price").value = "0";
  await el("price-form").fire("submit");
  assert.match(el("admin-price-status").textContent, /lớn hơn 0/);
  assert.equal(fetchFn.called("PUT /api/room-types/1/price").length, 0);

  for (const [status, pattern] of [[400, /Giá không hợp lệ/], [403, /không có quyền/], [404, /không tồn tại/]]) {
    const page = setup({ "PUT /api/room-types/1/price": response(status) });
    await page.loadInventory();
    page.el("price-room-type").value = "1";
    page.el("new-price").value = "1000";
    await page.el("price-form").fire("submit");
    assert.match(page.el("admin-price-status").textContent, pattern);
  }
});

test("Unconfirmed price response is not reported as saved", async () => {
  const { el, loadInventory } = setup({
    "PUT /api/room-types/1/price": response(200, { ...roomType, id: 1, basePrice: 1 })
  });
  await loadInventory();
  el("price-room-type").value = "1";
  el("new-price").value = "2000";
  await el("price-form").fire("submit");
  assert.match(el("admin-price-status").textContent, /chưa thể xác nhận/);
});

test("Writes are locked until the response arrives", async () => {
  let release;
  const { el, fetchFn, fillRoomType } = setup({
    "POST /api/hotels/1/room-types": () => new Promise(resolve => { release = resolve; })
  });
  fillRoomType();
  const first = el("create-room-type-form").fire("submit");
  await el("create-room-type-form").fire("submit");
  assert.equal(el("create-room-type-submit").disabled, true);
  assert.equal(el("price-submit").disabled, true);
  await new Promise(resolve => setImmediate(resolve));
  release(response(201, roomType));
  await first;
  assert.equal(fetchFn.called("POST /api/hotels/1/room-types").length, 1);
  assert.equal(el("create-room-type-submit").disabled, false);
});
