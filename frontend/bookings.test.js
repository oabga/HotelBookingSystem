const test = require("node:test");
const assert = require("node:assert/strict");
const api = require("./api.js");
const { initBookings } = require("./bookings.js");
const { FakeDocument, response, fakeFetch, fakeAuth, fixedNow, sampleBooking } = require("./test-support.js");

function setup(routes = {}, role = "USER") {
  const doc = new FakeDocument();
  const fetchFn = fakeFetch({ "GET /api/bookings": response(200, [sampleBooking()]), ...routes });
  const client = api.createClient({ fetchFn, auth: fakeAuth(role) });
  const bookings = initBookings(doc, { client, role, tenantId: 1, now: fixedNow });
  return { el: id => doc.getElementById(id), fetchFn, bookings };
}

test("List shows contract fields and an empty list is not an error", async () => {
  const { el, bookings } = setup();
  await bookings.load();
  const text = el("booking-list").textContent;
  for (const part of ["Booking #5", "Chờ thanh toán", "Loại phòng #1", "1 phòng", "2026-11-24 → 2026-11-26", "Hạn thanh toán"]) {
    assert.ok(text.includes(part), part);
  }
  const empty = setup({ "GET /api/bookings": response(200, []) });
  await empty.bookings.load();
  assert.match(empty.el("bookings-status").textContent, /Chưa có booking/);
});

test("Any booking from another tenant blocks the whole list", async () => {
  const { el, bookings } = setup({
    "GET /api/bookings": response(200, [sampleBooking(), sampleBooking({ id: 6, tenantId: 2 })])
  });
  await bookings.load();
  assert.equal(el("booking-list").children.length, 0);
  assert.match(el("bookings-status").textContent, /không hợp lệ/);
});

test("Pay and cancel appear for USER only while the order is PENDING_PAYMENT", async () => {
  const { el, bookings } = setup({
    "GET /api/bookings": response(200, [
      sampleBooking({ id: 1 }), sampleBooking({ id: 2, status: "CONFIRMED" }),
      sampleBooking({ id: 3, status: "CANCELLED" }), sampleBooking({ id: 4, status: "EXPIRED" })
    ])
  });
  await bookings.load();
  assert.equal(el("booking-list").buttons("Thanh toán").length, 1);
  assert.equal(el("booking-list").buttons("Huỷ đơn").length, 1);
});

test("ADMIN sees who booked but gets no pay or cancel action", async () => {
  const { el, bookings } = setup({}, "ADMIN");
  await bookings.load();
  assert.ok(el("booking-list").textContent.includes("Người đặt: user@example.test"));
  assert.equal(el("booking-list").button("Thanh toán"), null);
  assert.equal(el("booking-list").button("Huỷ đơn"), null);
});

test("Overdue PENDING_PAYMENT hides pay and tells the user it is waiting for the job", async () => {
  const { el, bookings } = setup({
    "GET /api/bookings": response(200, [sampleBooking({ paymentExpiresAt: "2026-11-04T04:00:00Z" })])
  });
  await bookings.load();
  assert.equal(el("booking-list").button("Thanh toán"), null);
  assert.ok(el("booking-list").textContent.includes("Đã quá hạn"));
});

test("Payment posts without a body and refreshes the list", async () => {
  const { el, fetchFn, bookings } = setup({
    "POST /api/bookings/5/payment": response(200, sampleBooking({ status: "CONFIRMED" }))
  });
  await bookings.load();
  await el("booking-list").button("Thanh toán").fire("click");
  assert.equal(fetchFn.called("POST /api/bookings/5/payment")[0].body, undefined);
  assert.match(el("booking-action-status").textContent, /đã được xác nhận/);
  assert.equal(fetchFn.called("GET /api/bookings").length, 2);
});

test("Cancel reports success and refreshes the list", async () => {
  const { el, fetchFn, bookings } = setup({
    "POST /api/bookings/5/cancel": response(200, sampleBooking({ status: "CANCELLED" }))
  });
  await bookings.load();
  await el("booking-list").button("Huỷ đơn").fire("click");
  assert.match(el("booking-action-status").textContent, /Đã huỷ booking #5/);
  assert.equal(fetchFn.called("GET /api/bookings").length, 2);
});

test("Action failures have specific messages and are not retried", async () => {
  const cases = [
    ["Thanh toán", "payment", 409, /đã thanh toán, đã huỷ hoặc đã quá hạn/],
    ["Thanh toán", "payment", 404, /không tồn tại/],
    ["Thanh toán", "payment", 403, /không có quyền/],
    ["Huỷ đơn", "cancel", 409, /chỉ huỷ được đơn đang chờ/],
    ["Huỷ đơn", "cancel", 403, /không có quyền/],
    ["Huỷ đơn", "cancel", 500, /HTTP 500/]
  ];
  for (const [label, action, status, pattern] of cases) {
    const key = "POST /api/bookings/5/" + action;
    const { el, fetchFn, bookings } = setup({ [key]: response(status) });
    await bookings.load();
    await el("booking-list").button(label).fire("click");
    assert.match(el("booking-action-status").textContent, pattern);
    assert.equal(fetchFn.called(key).length, 1);
  }
});

test("Expired session on an action does not trigger another request", async () => {
  const { el, fetchFn, bookings } = setup({ "POST /api/bookings/5/payment": response(401) });
  await bookings.load();
  await el("booking-list").button("Thanh toán").fire("click");
  assert.equal(el("booking-action-status").textContent, api.sessionExpired);
  assert.equal(fetchFn.called("GET /api/bookings").length, 1);
});

test("Lost connection on payment says the result is unknown", async () => {
  const { el, bookings } = setup({ "POST /api/bookings/5/payment": new Error("down") });
  await bookings.load();
  await el("booking-list").button("Thanh toán").fire("click");
  assert.match(el("booking-action-status").textContent, /Không rõ kết quả/);
});

test("Double click on an action sends one request and locks other actions", async () => {
  let release;
  const { el, fetchFn, bookings } = setup({
    "POST /api/bookings/5/payment": () => new Promise(resolve => { release = resolve; })
  });
  await bookings.load();
  const pay = el("booking-list").button("Thanh toán");
  const cancel = el("booking-list").button("Huỷ đơn");
  const first = pay.fire("click");
  await pay.fire("click");
  await cancel.fire("click");
  assert.equal(pay.disabled, true);
  assert.equal(cancel.disabled, true);
  assert.equal(el("bookings-reload").disabled, true);
  await new Promise(resolve => setImmediate(resolve));
  release(response(200, sampleBooking({ status: "CONFIRMED" })));
  await first;
  assert.equal(fetchFn.called("POST /api/bookings/5/payment").length, 1);
  assert.equal(fetchFn.called("POST /api/bookings/5/cancel").length, 0);
  assert.equal(el("bookings-reload").disabled, false);
});

test("Reload button fetches the list again", async () => {
  const { el, fetchFn, bookings } = setup();
  await bookings.load();
  await el("bookings-reload").fire("click");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fetchFn.called("GET /api/bookings").length, 2);
});
