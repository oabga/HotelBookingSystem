const test = require("node:test");
const assert = require("node:assert/strict");
const api = require("./api.js");
const { initCatalog } = require("./catalog.js");
const { FakeDocument, response, fakeFetch, fakeAuth, fixedNow, sampleHotel, sampleRoom, sampleBooking } = require("./test-support.js");

const AVAILABILITY = "GET /api/hotels/1/availability";

function setup(routes = {}, role = "USER") {
  const doc = new FakeDocument();
  const fetchFn = fakeFetch({
    "GET /api/hotels": response(200, [sampleHotel]),
    [AVAILABILITY]: response(200, [sampleRoom]),
    ...routes
  });
  const client = api.createClient({ fetchFn, auth: fakeAuth(role) });
  let reloads = 0;
  const bookings = { load: async () => { reloads += 1; } };
  const catalog = initCatalog(doc, { client, role, tenantId: 1, now: fixedNow }, bookings);
  const el = id => doc.getElementById(id);
  const fillForm = () => {
    el("hotel-select").value = "1";
    el("search-check-in").value = "2026-11-24";
    el("search-check-out").value = "2026-11-26";
  };
  const search = async () => { fillForm(); await el("search-form").fire("submit"); };
  return { doc, el, fetchFn, catalog, fillForm, search, reloads: () => reloads };
}

test("Hotels fill the select and no search runs until the button is pressed", async () => {
  const { el, fetchFn, catalog } = setup();
  await catalog.loadHotels();
  assert.equal(el("hotel-select").children.length, 2);
  assert.equal(el("hotel-select").children[1].value, "1");
  assert.equal(el("search-fields").disabled, false);
  assert.equal(fetchFn.called(AVAILABILITY).length, 0);
});

test("Empty, cross-tenant and failed hotel responses are reported", async () => {
  const empty = setup({ "GET /api/hotels": response(200, []) });
  await empty.catalog.loadHotels();
  assert.match(empty.el("hotels-status").textContent, /chưa có khách sạn/);
  const cross = setup({ "GET /api/hotels": response(200, [{ ...sampleHotel, tenantId: 2 }]) });
  await cross.catalog.loadHotels();
  assert.match(cross.el("hotels-status").textContent, /không hợp lệ/);
  assert.equal(cross.el("hotel-select").children.length, 0);
  const expired = setup({ "GET /api/hotels": response(401) });
  await expired.catalog.loadHotels();
  assert.equal(expired.el("hotels-status").textContent, api.sessionExpired);
});

test("Search sends date-only query and renders availability", async () => {
  const { el, fetchFn, search } = setup();
  await search();
  assert.equal(fetchFn.calls[0].url, "http://localhost:8080/api/hotels/1/availability?checkIn=2026-11-24&checkOut=2026-11-26");
  const text = el("result-list").textContent;
  for (const part of ["Standard", "Tối đa 2 khách", "còn 3 phòng", "2 đêm"]) assert.ok(text.includes(part), part);
});

test("Invalid search input never reaches the API", async () => {
  const { el, fetchFn, fillForm } = setup();
  fillForm();
  el("search-check-in").value = "2026-01-01";
  await el("search-form").fire("submit");
  assert.match(el("search-status").textContent, /quá khứ/);
  el("hotel-select").value = "";
  await el("search-form").fire("submit");
  assert.match(el("search-status").textContent, /Chọn khách sạn/);
  assert.equal(fetchFn.called(AVAILABILITY).length, 0);
});

test("Search reports empty result and each error status", async () => {
  const empty = setup({ [AVAILABILITY]: response(200, []) });
  await empty.search();
  assert.match(empty.el("search-status").textContent, /Không có loại phòng/);
  for (const [status, pattern] of [[400, /Khoảng ngày/], [404, /Khách sạn không tồn tại/], [401, /hết hạn/], [500, /HTTP 500/]]) {
    const page = setup({ [AVAILABILITY]: response(status) });
    await page.search();
    assert.match(page.el("search-status").textContent, pattern);
  }
});

test("Room names render as text, not HTML", async () => {
  const name = "<img src=x onerror=alert(1)>";
  const { el, search } = setup({ [AVAILABILITY]: response(200, [{ ...sampleRoom, name }]) });
  await search();
  const strong = el("result-list").find(node => node.tag === "strong");
  assert.equal(strong.textContent, name);
  assert.equal(strong.innerHTML, undefined);
});

test("ADMIN sees search results but no booking button", async () => {
  const { el, search } = setup({}, "ADMIN");
  await search();
  assert.ok(el("result-list").textContent.includes("Standard"));
  assert.equal(el("result-list").button("Đặt phòng"), null);
});

test("USER booking sends the v3.0 body with the searched dates", async () => {
  const { el, fetchFn, search, reloads } = setup({ "POST /api/bookings": response(201, sampleBooking()) });
  await search();
  await el("result-list").button("Đặt phòng").fire("click");
  assert.deepEqual(fetchFn.called("POST /api/bookings")[0].body, {
    roomTypeId: 1, checkIn: "2026-11-24", checkOut: "2026-11-26", roomCount: 1
  });
  assert.match(el("booking-status").textContent, /Đã tạo booking #5/);
  assert.equal(reloads(), 1, "booking list refreshes");
});

test("Availability is not searched again automatically after booking", async () => {
  const { el, fetchFn, search } = setup({ "POST /api/bookings": response(201, sampleBooking()) });
  await search();
  await el("result-list").button("Đặt phòng").fire("click");
  assert.equal(fetchFn.called(AVAILABILITY).length, 1);
  assert.equal(el("result-list").children.length, 0, "stale results are removed");
  assert.match(el("search-status").textContent, /Bấm Tìm kiếm/);
});

test("Double click creates one request and buttons stay locked until the response", async () => {
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const { el, fetchFn, search } = setup({
    [AVAILABILITY]: response(200, [sampleRoom, { ...sampleRoom, roomTypeId: 2, name: "Deluxe" }]),
    "POST /api/bookings": () => pending
  });
  await search();
  const buttons = el("result-list").buttons("Đặt phòng");
  const first = buttons[0].fire("click");
  const second = buttons[0].fire("click");
  const other = buttons[1].fire("click");
  assert.ok(buttons.every(button => button.disabled), "every booking button is locked");
  release(response(409));
  await Promise.all([first, second, other]);
  assert.equal(fetchFn.called("POST /api/bookings").length, 1);
});

test("Room count is validated against what is available and the 10 room limit", async () => {
  const { el, fetchFn, search } = setup();
  await search();
  const input = el("result-list").find(node => node.tag === "input");
  assert.equal(input.max, "3");
  for (const bad of ["0", "4", "1.5", ""]) {
    input.value = bad;
    await el("result-list").button("Đặt phòng").fire("click");
    assert.match(el("booking-status").textContent, /từ 1 đến 3/);
  }
  assert.equal(fetchFn.called("POST /api/bookings").length, 0);

  const many = setup({ [AVAILABILITY]: response(200, [{ ...sampleRoom, availableRooms: 25 }]) });
  await many.search();
  assert.equal(many.el("result-list").find(node => node.tag === "input").max, "10");
});

test("Every booking failure has its own message and is never retried", async () => {
  const cases = [
    [400, /không hợp lệ/], [401, /hết hạn/], [403, /không có quyền/], [404, /không tồn tại/],
    [409, /Không đủ phòng/], [500, /HTTP 500/]
  ];
  for (const [status, pattern] of cases) {
    const { el, fetchFn, search } = setup({ "POST /api/bookings": response(status) });
    await search();
    await el("result-list").button("Đặt phòng").fire("click");
    assert.match(el("booking-status").textContent, pattern, String(status));
    assert.equal(fetchFn.called("POST /api/bookings").length, 1);
  }
});

test("Unknown booking outcome warns against double booking", async () => {
  for (const route of [new Error("down"), response(201, { id: 1, status: "CONFIRMED" }), response(201)]) {
    const { el, search } = setup({ "POST /api/bookings": route });
    await search();
    await el("result-list").button("Đặt phòng").fire("click");
    assert.match(el("booking-status").textContent, /Booking của tôi trước khi đặt lại/);
  }
});

test("Failed booking other than 409 unlocks the buttons again", async () => {
  const { el, search } = setup({ "POST /api/bookings": response(500) });
  await search();
  await el("result-list").button("Đặt phòng").fire("click");
  assert.equal(el("result-list").button("Đặt phòng").disabled, false);
});

test("Changing hotel or dates clears results so old dates cannot be booked", async () => {
  for (const id of ["hotel-select", "search-check-in", "search-check-out"]) {
    const { el, search } = setup();
    await search();
    await el(id).fire("change");
    assert.equal(el("result-list").children.length, 0, id);
    assert.match(el("search-status").textContent, /Điều kiện đã đổi/);
  }
});

test("A slow search answer is ignored once the conditions changed", async () => {
  let release;
  const { el, search } = setup({ [AVAILABILITY]: () => new Promise(resolve => { release = resolve; }) });
  const running = search();
  await new Promise(resolve => setImmediate(resolve));
  await el("search-check-out").fire("change");
  release(response(200, [sampleRoom]));
  await running;
  assert.equal(el("result-list").children.length, 0);
});
