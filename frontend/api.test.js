const test = require("node:test");
const assert = require("node:assert/strict");
const api = require("./api.js");
const { fakeFetch, fakeAuth, response, sampleBooking, sampleHotel, sampleRoom } = require("./test-support.js");

const now = new Date("2026-11-04T05:00:00Z");

test("Stay rules follow contract: past, order, max 30 nights, real dates only", () => {
  assert.equal(api.validateStay("2026-11-24", "2026-11-26", { now }), null);
  assert.equal(api.validateStay("2026-11-04", "2026-11-05", { now }), null, "today is valid");
  assert.equal(api.validateStay("2026-12-01", "2026-12-31", { now }), null, "exactly 30 nights");
  assert.match(api.validateStay("2026-11-03", "2026-11-05", { now }), /quá khứ/);
  assert.match(api.validateStay("2026-12-01", "2026-12-01", { now }), /sau ngày nhận/);
  assert.match(api.validateStay("2026-12-01", "2027-01-01", { now }), /tối đa 30/);
  assert.match(api.validateStay("2026-02-30", "2026-03-02", { now }), /không hợp lệ/);
  assert.match(api.validateStay("2026-12-01T00:00:00Z", "2026-12-02", { now }), /không hợp lệ/);
  assert.match(api.validateStay("", "2026-12-02", { now }), /Chọn đủ/);
});

test("Inventory range may be in the past but keeps the other rules", () => {
  assert.equal(api.validateStay("2026-01-01", "2026-01-03", { allowPast: true, now }), null);
  assert.match(api.validateStay("2026-01-03", "2026-01-01", { allowPast: true, now }), /sau ngày nhận/);
});

test("Today is computed in Vietnam time, not UTC", () => {
  assert.equal(api.todayInVietnam(new Date("2026-11-04T18:00:00Z")), "2026-11-05");
});

test("Price and room type limits match contract 4.4", () => {
  for (const good of ["1", "0.01", "99999999.99", "500000"]) assert.equal(api.validatePrice(good), null, good);
  for (const bad of ["0", "-1", "100000000", "1.234", "abc", ""]) assert.notEqual(api.validatePrice(bad), null, bad);
  assert.equal(api.validateNewRoomType("Suite", "4", "2500000", "2"), null);
  assert.match(api.validateNewRoomType("  ", "4", "1", "2"), /tên/i);
  assert.match(api.validateNewRoomType("x".repeat(101), "4", "1", "2"), /100/);
  for (const capacity of ["0", "21", "1.5"]) assert.match(api.validateNewRoomType("S", capacity, "1", "2"), /khách/);
  for (const total of ["0", "1001"]) assert.match(api.validateNewRoomType("S", "1", "1", total), /Số phòng/);
});

test("Validators accept contract payloads and reject the old v2.0 shapes", () => {
  assert.equal(api.isHotel(sampleHotel, 1), true);
  assert.equal(api.isHotel(sampleHotel, 2), false, "other tenant");
  assert.equal(api.isAvailability(sampleRoom), true);
  assert.equal(api.isBooking(sampleBooking(), 1), true);
  assert.equal(api.isBooking(sampleBooking({ tenantId: 2 }), 1), false);
  assert.equal(api.isBooking(sampleBooking({ status: "PAID" }), 1), false);
  assert.equal(api.isBooking(sampleBooking({ checkIn: "2026-11-24T00:00:00Z" }), 1), false);
  assert.equal(api.isBooking({ id: 1, tenantId: 1, roomId: 1, status: "CONFIRMED" }, 1), false, "v2.0 booking");
  assert.equal(api.isAvailability({ id: 1, tenantId: 1, name: "Room", price: 1 }), false, "v2.0 room");
});

test("Client sends Bearer through Gateway /api and JSON only with a body", async () => {
  const fetchFn = fakeFetch({ "POST /api/bookings/5/payment": response(200, {}), "POST /api/bookings": response(201, {}) });
  const client = api.createClient({ fetchFn, auth: fakeAuth() });
  await client.post("/bookings/5/payment");
  await client.post("/bookings", { roomTypeId: 1 });
  assert.equal(fetchFn.calls[0].url, "http://localhost:8080/api/bookings/5/payment");
  assert.equal(fetchFn.calls[0].init.headers.Authorization, "Bearer test-jwt");
  assert.equal(fetchFn.calls[0].init.headers["Content-Type"], undefined);
  assert.equal(fetchFn.calls[0].init.body, undefined);
  assert.equal(fetchFn.calls[1].init.headers["Content-Type"], "application/json");
});

test("Client does not call the API without a token", async () => {
  const fetchFn = fakeFetch({});
  const result = await api.createClient({ fetchFn, auth: fakeAuth(null) }).get("/hotels");
  assert.deepEqual(result, { kind: "signed-out" });
  assert.equal(fetchFn.calls.length, 0);
});

test("Client separates 401, HTTP errors, network failure and malformed JSON", async () => {
  let expired = 0;
  const fetchFn = fakeFetch({
    "GET /api/a": response(401), "GET /api/b": response(500), "GET /api/c": new Error("down"), "GET /api/d": response(200)
  });
  const client = api.createClient({ fetchFn, auth: fakeAuth(), onUnauthorized: () => { expired += 1; } });
  const unauthorized = await client.get("/a");
  assert.equal(expired, 1);
  assert.equal(api.describeFailure(unauthorized), api.sessionExpired);
  assert.match(api.describeFailure(await client.get("/b")), /HTTP 500/);
  assert.equal((await client.get("/c")).kind, "network");
  assert.equal((await client.get("/d")).kind, "invalid");
  assert.equal(expired, 1, "only a 401 counts as an expired session");
});
