const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const api = require("./api.js");
const { initSupport } = require("./support.js");
const { FakeDocument, response, fakeFetch, fakeAuth } = require("./test-support.js");

const REQUESTS = "GET /api/support/date-change-requests";
const CREATE = "POST /api/support/date-change-requests";
const APPROVE = "POST /api/support/date-change-requests/9/approve";
const REJECT = "POST /api/support/date-change-requests/9/reject";
const BOOKINGS = "GET /api/bookings/reschedulable";
const QUOTE = "GET /api/bookings/5/reschedule-quote";
const NOTIFICATIONS = "GET /api/notifications";

const booking = (overrides = {}) => ({
  id: 5, roomTypeId: 1, roomTypeName: "Standard", hotelName: "Hà Nội",
  checkIn: "2030-12-01", checkOut: "2030-12-03", roomCount: 2, totalAmount: 2000000, ...overrides
});
const quote = (overrides = {}) => ({
  bookingId: 5, roomTypeId: 1, roomTypeName: "Standard", currentCheckIn: "2030-12-01", currentCheckOut: "2030-12-03",
  checkIn: "2030-12-10", checkOut: "2030-12-13", nights: 3, roomCount: 2,
  currentTotalAmount: 2000000, newTotalAmount: 3000000, available: true, ...overrides
});
const request = (overrides = {}) => ({
  id: 9, tenantId: 1, bookingId: 5, requesterEmail: "user@example.test", roomTypeName: "Standard", roomCount: 2,
  currentCheckIn: "2030-12-01", currentCheckOut: "2030-12-03", requestedCheckIn: "2030-12-10", requestedCheckOut: "2030-12-13",
  currentTotalAmount: 2000000, newTotalAmount: 3000000, description: "Đổi vì công tác", status: "PENDING",
  decisionNote: null, decidedAt: null, createdAt: "2030-11-04T05:00:00Z", ...overrides
});
const notification = (overrides = {}) => ({
  id: 3, type: "DATE_CHANGE_APPROVED", title: "Yêu cầu đổi ngày đã được duyệt", body: "Booking #5 đã đổi sang 2030-12-10.",
  referenceId: 9, readAt: null, createdAt: "2030-11-04T06:00:00Z", ...overrides
});
const page = (items, overrides = {}) => ({ items, page: 0, size: 10, totalElements: items.length, totalPages: items.length ? 1 : 0, ...overrides });
const inbox = (items, unreadCount = items.filter(item => item.readAt === null).length) => ({ ...page(items), unreadCount });

function fakeTimers() {
  const timers = { started: [], cleared: [] };
  timers.setInterval = (handler, millis) => {
    timers.started.push({ handler, millis });
    return timers.started.length;
  };
  timers.clearInterval = id => timers.cleared.push(id);
  return timers;
}

function setup(routes = {}, role = "USER") {
  const doc = new FakeDocument("app3");
  const fetchFn = fakeFetch({
    [REQUESTS]: response(200, page([])),
    [BOOKINGS]: response(200, [booking()]),
    [NOTIFICATIONS]: response(200, inbox([])),
    [QUOTE]: response(200, quote()),
    ...routes
  });
  const timers = fakeTimers();
  const app = initSupport(doc, fakeAuth(role), fetchFn, timers);
  const el = id => doc.getElementById(id);
  const fill = () => {
    el("change-booking").value = "5";
    el("change-check-in").value = "2030-12-10";
    el("change-check-out").value = "2030-12-13";
  };
  const check = async () => {
    await app.ready;
    fill();
    await el("check-button").fire("click");
    await tick();
  };
  const submit = async (description = "Đổi vì công tác") => {
    await check();
    el("change-description").value = description;
    await el("change-form").fire("submit");
  };
  return { doc, el, fetchFn, app, timers, fill, check, submit };
}

const tick = () => new Promise(resolve => setImmediate(resolve));

// ---------- Khớp với HTML và server ----------

test("Every element id used by the script exists in the Support Center page", () => {
  const html = fs.readFileSync(path.join(__dirname, "app3/index.html"), "utf8");
  const scripts = ["support.js", "auth.js"].map(file => fs.readFileSync(path.join(__dirname, file), "utf8")).join("\n");
  const used = [...scripts.matchAll(/getElementById\("([^"]+)"\)/g)].map(match => match[1]);
  assert.ok(used.length > 20);
  for (const id of new Set(used)) assert.match(html, new RegExp('id="' + id + '"'), id);
});

test("Static server serves the Support Center on port 3002", () => {
  const server = fs.readFileSync(path.join(__dirname, "server.js"), "utf8");
  assert.match(server, /\[3002, "app3"\]/);
  assert.match(server, /"\/support\.js"/);
});

test("Support Center no longer calls the removed ticket API", () => {
  const source = fs.readFileSync(path.join(__dirname, "support.js"), "utf8");
  assert.doesNotMatch(source, /support\/tickets/);
});

// ---------- Đăng nhập và role ----------

test("Signed-out page calls no API and shows no form", () => {
  const doc = new FakeDocument("app3");
  const fetchFn = fakeFetch({});
  const timers = fakeTimers();
  assert.equal(initSupport(doc, fakeAuth(null), fetchFn, timers), null);
  assert.equal(fetchFn.calls.length, 0);
  assert.equal(timers.started.length, 0);
  assert.equal(doc.getElementById("create-panel").hidden, true);
  assert.equal(doc.getElementById("notification-panel").hidden, true);
});

test("USER gets the form, notifications and polling, but no filter", async () => {
  const { el, app, timers } = setup();
  await app.ready;
  assert.equal(el("create-panel").hidden, false);
  assert.equal(el("notification-panel").hidden, false);
  assert.equal(el("filter-wrap").hidden, true);
  assert.equal(el("requests-title").textContent, "Phiếu của tôi");
  assert.equal(timers.started[0].millis, 15000);
});

test("ADMIN gets only the request queue", async () => {
  const { el, fetchFn, app, timers } = setup({}, "ADMIN");
  await app.ready;
  assert.equal(el("create-panel").hidden, true);
  assert.equal(el("notification-panel").hidden, true);
  assert.equal(el("filter-wrap").hidden, false);
  assert.equal(el("requests-title").textContent, "Phiếu cần xử lý");
  assert.equal(fetchFn.called(BOOKINGS).length, 0);
  assert.equal(fetchFn.called(NOTIFICATIONS).length, 0);
  assert.equal(timers.started.length, 0);
});

test("A 401 asks to log in again and stops polling", async () => {
  const { el, app, timers } = setup({ [NOTIFICATIONS]: response(401) });
  await app.ready;
  assert.equal(el("login-status").textContent, api.sessionExpired);
  assert.equal(el("notification-status").textContent, api.sessionExpired);
  await tick();
  assert.deepEqual(timers.cleared, [1]);
});

// ---------- Chọn đơn ----------

test("Booking options show the room type, hotel and current dates", async () => {
  const { el, app } = setup();
  await app.ready;
  assert.deepEqual(el("change-booking").children.map(option => option.value), ["", "5"]);
  const label = el("change-booking").children[1].textContent;
  for (const part of ["Standard", "Hà Nội", "2030-12-01", "2030-12-03", "2 phòng"]) assert.ok(label.includes(part), part);
});

test("No eligible booking explains why", async () => {
  const { el, app } = setup({ [BOOKINGS]: response(200, []) });
  await app.ready;
  assert.match(el("bookings-note").textContent, /đã thanh toán và chưa tới ngày nhận phòng/);
});

test("Invalid booking data is reported", async () => {
  const { el, app } = setup({ [BOOKINGS]: response(200, [{ id: "x" }]) });
  await app.ready;
  assert.match(el("bookings-note").textContent, /không hợp lệ/);
  assert.equal(el("change-booking").children.length, 0);
});

// ---------- Kiểm tra còn phòng ----------

test("Sending stays disabled until a check finds rooms", async () => {
  const { el, fetchFn, app, fill } = setup();
  await app.ready;
  assert.equal(el("change-submit").disabled, true);
  assert.equal(fetchFn.called(QUOTE).length, 0, "no check before the button");
  fill();
  await el("check-button").fire("click");
  await tick();
  assert.equal(fetchFn.called(QUOTE)[0].url, "http://localhost:8080/api/bookings/5/reschedule-quote?checkIn=2030-12-10&checkOut=2030-12-13");
  assert.equal(el("change-submit").disabled, false);
  const text = el("quote-result").textContent;
  for (const part of ["Còn phòng", "3 đêm", "2 phòng", "chưa được giữ"]) assert.ok(text.includes(part), part);
  assert.match(text, /2\.000\.000 VND → 3\.000\.000 VND/);
});

test("No rooms keeps sending disabled", async () => {
  const { el, check } = setup({ [QUOTE]: response(200, quote({ available: false })) });
  await check();
  assert.match(el("quote-result").textContent, /Không đủ phòng/);
  assert.equal(el("change-submit").disabled, true);
});

test("Changing booking or dates requires checking again", async () => {
  for (const id of ["change-booking", "change-check-in", "change-check-out"]) {
    const { el, check } = setup();
    await check();
    assert.equal(el("change-submit").disabled, false);
    await el(id).fire("change");
    assert.equal(el("change-submit").disabled, true, id);
    assert.equal(el("quote-result").textContent, "");
  }
});

test("A slow check answer is ignored once the input changed", async () => {
  let release;
  const { el, app, fill } = setup({ [QUOTE]: () => new Promise(resolve => { release = resolve; }) });
  await app.ready;
  fill();
  const running = el("check-button").fire("click");
  await tick();
  await el("change-check-out").fire("change");
  release(response(200, quote()));
  await running;
  assert.equal(el("change-submit").disabled, true);
  assert.equal(el("quote-result").textContent, "");
});

test("Invalid input never reaches the API", async () => {
  const cases = [
    [() => {}, /Chọn đơn/],
    [el => { el("change-booking").value = "5"; }, /Chọn đủ/],
    [el => { el("change-booking").value = "5"; el("change-check-in").value = "2020-01-01"; el("change-check-out").value = "2020-01-03"; }, /quá khứ/],
    [el => { el("change-booking").value = "5"; el("change-check-in").value = "2030-12-05"; el("change-check-out").value = "2030-12-05"; }, /sau ngày nhận/],
    [el => { el("change-booking").value = "5"; el("change-check-in").value = "2030-12-01"; el("change-check-out").value = "2031-01-05"; }, /tối đa 30/],
    [el => { el("change-booking").value = "5"; el("change-check-in").value = "2030-12-01"; el("change-check-out").value = "2030-12-03"; }, /phải khác/]
  ];
  for (const [prepare, pattern] of cases) {
    const { el, fetchFn, app } = setup();
    await app.ready;
    prepare(el);
    await el("check-button").fire("click");
    assert.match(el("quote-result").textContent, pattern);
    assert.equal(fetchFn.called(QUOTE).length, 0);
  }
});

test("Check failures have their own messages", async () => {
  const cases = [[response(400), /không hợp lệ/], [response(404), /Không tìm thấy/], [response(409), /không còn đổi được/], [response(500), /HTTP 500/], [new Error("down"), /Không kết nối/], [response(200, { x: 1 }), /Không đọc được/]];
  for (const [route, pattern] of cases) {
    const { el, check } = setup({ [QUOTE]: route });
    await check();
    assert.match(el("quote-result").textContent, pattern);
    assert.equal(el("change-submit").disabled, true);
  }
});

// ---------- Gửi yêu cầu ----------

test("Sending posts only the checked values and the description", async () => {
  const { el, fetchFn, submit } = setup({ [CREATE]: response(201, request()) });
  await submit("  Đổi vì công tác ");
  assert.deepEqual(fetchFn.called(CREATE)[0].body, {
    bookingId: 5, checkIn: "2030-12-10", checkOut: "2030-12-13", description: "Đổi vì công tác"
  });
  assert.match(el("change-status").textContent, /Đã gửi phiếu #9/);
  assert.equal(el("change-description").value, "");
  assert.equal(el("change-submit").disabled, true, "next request needs a new check");
  assert.equal(fetchFn.called(REQUESTS).length, 2, "list refreshes");
});

test("Description is required, plain text and limited", async () => {
  for (const [text, pattern] of [["  ", /Nhập mô tả/], ["a".repeat(4001), /4000/], ["<b>x</b>", /HTML/], ["a\u0000b", /ký tự/]]) {
    const { el, fetchFn, submit } = setup();
    await submit(text);
    assert.match(el("change-status").textContent, pattern);
    assert.equal(fetchFn.called(CREATE).length, 0);
  }
});

test("A less-than sign in plain text is allowed", async () => {
  const { fetchFn, submit } = setup({ [CREATE]: response(201, request()) });
  await submit("Giá < 500000");
  assert.equal(fetchFn.called(CREATE).length, 1);
});

test("Send failures keep the text, explain, and are not retried", async () => {
  const cases = [[response(400), /không hợp lệ/], [response(403), /chưa được cấp/], [response(404), /Không tìm thấy/], [response(409), /đang chờ duyệt, hoặc phòng vừa hết/], [response(503), /Chưa có phiếu nào/], [new Error("down"), /Không rõ phiếu/], [response(201, { id: 1 }), /chưa thể xác nhận/]];
  for (const [route, pattern] of cases) {
    const { el, fetchFn, submit } = setup({ [CREATE]: route });
    await submit();
    assert.match(el("change-status").textContent, pattern);
    assert.equal(el("change-description").value, "Đổi vì công tác");
    assert.equal(fetchFn.called(CREATE).length, 1);
  }
});

test("A 409 requires checking rooms again", async () => {
  const { el, submit } = setup({ [CREATE]: response(409) });
  await submit();
  assert.equal(el("change-submit").disabled, true);
  assert.match(el("quote-result").textContent, /kiểm tra lại/);
});

test("Double submit sends one request and locks actions until the response", async () => {
  let release;
  const { el, fetchFn, check } = setup({ [CREATE]: () => new Promise(resolve => { release = resolve; }) });
  await check();
  el("change-description").value = "x";
  const first = el("change-form").fire("submit");
  await el("change-form").fire("submit");
  assert.equal(el("change-submit").disabled, true);
  assert.equal(el("check-button").disabled, true);
  assert.equal(el("requests-reload").disabled, true);
  await tick();
  release(response(500));
  await first;
  assert.equal(fetchFn.called(CREATE).length, 1);
  assert.equal(el("change-submit").disabled, false);
});

// ---------- Danh sách phiếu ----------

test("USER sees request details without decision buttons", async () => {
  const { el, app } = setup({ [REQUESTS]: response(200, page([request()])) });
  await app.ready;
  const text = el("request-list").textContent;
  for (const part of ["Phiếu #9", "Standard", "Chờ duyệt", "Booking #5", "2030-12-01 → 2030-12-03", "2030-12-10 → 2030-12-13", "Đổi vì công tác"]) {
    assert.ok(text.includes(part), part);
  }
  assert.ok(!text.includes("Người gửi"));
  assert.equal(el("request-list").button("Duyệt"), null);
});

test("Decided requests show the reason and time", async () => {
  const decided = request({ status: "REJECTED", decisionNote: "Bảo trì", decidedAt: "2030-11-04T07:00:00Z" });
  const { el, app } = setup({ [REQUESTS]: response(200, page([decided])) });
  await app.ready;
  const text = el("request-list").textContent;
  for (const part of ["Bị từ chối", "Lý do: Bảo trì", "Xử lý lúc"]) assert.ok(text.includes(part), part);
});

test("Text is rendered as text, not HTML", async () => {
  const description = "<img src=x onerror=alert(1)>";
  const { el, app } = setup({ [REQUESTS]: response(200, page([request({ description })])) });
  await app.ready;
  const node = el("request-list").find(item => item.textContent === description);
  assert.ok(node);
  assert.equal(node.innerHTML, undefined);
});

test("A request from another tenant blocks the whole list", async () => {
  const { el, app } = setup({ [REQUESTS]: response(200, page([request(), request({ id: 10, tenantId: 2 })])) });
  await app.ready;
  assert.equal(el("request-list").children.length, 0);
  assert.match(el("requests-status").textContent, /không hợp lệ/);
});

test("ADMIN queue starts from the selected filter and can change it", async () => {
  const { el, fetchFn, doc } = (() => {
    const doc = new FakeDocument("app3");
    doc.getElementById("status-filter").value = "PENDING";
    const fetchFn = fakeFetch({ [REQUESTS]: response(200, page([request()])) });
    const app = initSupport(doc, fakeAuth("ADMIN"), fetchFn, fakeTimers());
    return { el: id => doc.getElementById(id), fetchFn, doc, app };
  })();
  await tick();
  assert.match(fetchFn.called(REQUESTS)[0].url, /status=PENDING/);
  el("status-filter").value = "";
  await el("status-filter").fire("change");
  await tick();
  assert.doesNotMatch(fetchFn.called(REQUESTS)[1].url, /status=/);
  assert.ok(doc);
});

test("Pager moves between pages and stops at the ends", async () => {
  const first = response(200, page([request()], { totalElements: 11, totalPages: 2 }));
  const second = response(200, page([request({ id: 8 })], { page: 1, totalElements: 11, totalPages: 2 }));
  const { el, fetchFn, app } = setup({ [REQUESTS]: [first, second, first] });
  await app.ready;
  assert.equal(el("prev-page").disabled, true);
  assert.equal(el("page-info").textContent, "Trang 1/2");
  await el("next-page").fire("click");
  await tick();
  assert.match(fetchFn.called(REQUESTS)[1].url, /page=1&size=10/);
  assert.equal(el("next-page").disabled, true);
  await el("prev-page").fire("click");
  await tick();
  assert.match(fetchFn.called(REQUESTS)[2].url, /page=0&size=10/);
});

// ---------- Duyệt và từ chối ----------

function adminWith(routes) {
  return setup({ [REQUESTS]: response(200, page([request()])), ...routes }, "ADMIN");
}

test("ADMIN sees the requester and decision buttons only while pending", async () => {
  const pending = adminWith({});
  await pending.app.ready;
  assert.ok(pending.el("request-list").textContent.includes("Người gửi: user@example.test"));
  assert.ok(pending.el("request-list").button("Duyệt"));
  assert.ok(pending.el("request-list").button("Từ chối"));

  const decided = adminWith({ [REQUESTS]: response(200, page([request({ status: "APPROVED", decidedAt: "2030-11-04T07:00:00Z" })])) });
  await decided.app.ready;
  assert.equal(decided.el("request-list").button("Duyệt"), null);
});

test("Approve calls the API and refreshes the list", async () => {
  const { el, fetchFn, app } = adminWith({ [APPROVE]: response(200, request({ status: "APPROVED", decidedAt: "2030-11-04T07:00:00Z" })) });
  await app.ready;
  await el("request-list").button("Duyệt").fire("click");
  await tick();
  assert.equal(fetchFn.called(APPROVE).length, 1);
  assert.equal(fetchFn.called(APPROVE)[0].body, undefined);
  assert.match(el("decision-status").textContent, /Đã duyệt phiếu #9/);
  assert.equal(fetchFn.called(REQUESTS).length, 2);
});

test("Reject sends the trimmed reason, or no body without one", async () => {
  const decided = response(200, request({ status: "REJECTED", decidedAt: "2030-11-04T07:00:00Z" }));
  const withReason = adminWith({ [REJECT]: decided });
  await withReason.app.ready;
  withReason.el("request-list").find(node => node.tag === "input").value = "  Bảo trì ";
  await withReason.el("request-list").button("Từ chối").fire("click");
  await tick();
  assert.deepEqual(withReason.fetchFn.called(REJECT)[0].body, { note: "Bảo trì" });
  assert.match(withReason.el("decision-status").textContent, /Đã từ chối phiếu #9/);

  const withoutReason = adminWith({ [REJECT]: decided });
  await withoutReason.app.ready;
  await withoutReason.el("request-list").button("Từ chối").fire("click");
  await tick();
  assert.equal(withoutReason.fetchFn.called(REJECT)[0].body, undefined);
});

test("Invalid reason never reaches the API", async () => {
  for (const [note, pattern] of [["<b>x", /HTML/], ["a".repeat(1001), /1000/]]) {
    const { el, fetchFn, app } = adminWith({});
    await app.ready;
    el("request-list").find(node => node.tag === "input").value = note;
    await el("request-list").button("Từ chối").fire("click");
    assert.match(el("decision-status").textContent, pattern);
    assert.equal(fetchFn.called(REJECT).length, 0);
  }
});

test("Approval failures explain that the booking did not change", async () => {
  const cases = [[response(409), /đã hết phòng.*chưa đổi/], [response(503), /vẫn chờ duyệt/], [response(403), /không có quyền/], [response(404), /không tồn tại/], [new Error("down"), /Không rõ kết quả/], [response(200, request()), /chưa thể xác nhận/]];
  for (const [route, pattern] of cases) {
    const { el, fetchFn, app } = adminWith({ [APPROVE]: route });
    await app.ready;
    await el("request-list").button("Duyệt").fire("click");
    await tick();
    assert.match(el("decision-status").textContent, pattern);
    assert.equal(fetchFn.called(APPROVE).length, 1);
  }
});

test("Double click decides once and locks the other buttons", async () => {
  let release;
  const { el, fetchFn, app } = adminWith({ [APPROVE]: () => new Promise(resolve => { release = resolve; }) });
  await app.ready;
  const approve = el("request-list").button("Duyệt");
  const reject = el("request-list").button("Từ chối");
  const first = approve.fire("click");
  await approve.fire("click");
  await reject.fire("click");
  assert.equal(approve.disabled, true);
  assert.equal(reject.disabled, true);
  await tick();
  release(response(409));
  await first;
  await tick();
  assert.equal(fetchFn.called(APPROVE).length, 1);
  assert.equal(fetchFn.called(REJECT).length, 0);
});

// ---------- Thông báo ----------

test("Notifications show content, unread count and a read action", async () => {
  const { el, app } = setup({ [NOTIFICATIONS]: response(200, inbox([notification(), notification({ id: 2, readAt: "2030-11-04T06:30:00Z" })])) });
  await app.ready;
  assert.equal(el("notification-count").textContent, "1 chưa đọc");
  const text = el("notification-list").textContent;
  for (const part of ["đã được duyệt", "Booking #5"]) assert.ok(text.includes(part), part);
  assert.equal(el("notification-list").buttons("Đánh dấu đã đọc").length, 1);
});

test("Empty notifications say so", async () => {
  const { el, app } = setup();
  await app.ready;
  assert.equal(el("notification-status").textContent, "Chưa có thông báo.");
  assert.equal(el("notification-count").textContent, "");
});

test("Marking as read posts and refreshes the count", async () => {
  const { el, fetchFn, app } = setup({
    [NOTIFICATIONS]: [response(200, inbox([notification()])), response(200, inbox([notification({ readAt: "2030-11-04T06:30:00Z" })]))],
    "POST /api/notifications/3/read": response(200, notification({ readAt: "2030-11-04T06:30:00Z" }))
  });
  await app.ready;
  await el("notification-list").button("Đánh dấu đã đọc").fire("click");
  await tick();
  assert.equal(fetchFn.called("POST /api/notifications/3/read").length, 1);
  assert.equal(el("notification-count").textContent, "");
});

test("Polling shows a new notification and refreshes requests and bookings", async () => {
  const { el, fetchFn, app } = setup({
    [NOTIFICATIONS]: [response(200, inbox([])), response(200, inbox([notification()]))]
  });
  await app.ready;
  await app.poll();
  assert.ok(el("notification-list").textContent.includes("đã được duyệt"));
  assert.equal(fetchFn.called(REQUESTS).length, 2);
  assert.equal(fetchFn.called(BOOKINGS).length, 2);
});

test("Polling without anything new does not reload the rest", async () => {
  const { fetchFn, app } = setup({ [NOTIFICATIONS]: response(200, inbox([notification()])) });
  await app.ready;
  await app.poll();
  assert.equal(fetchFn.called(REQUESTS).length, 1);
  assert.equal(fetchFn.called(BOOKINGS).length, 1);
});

test("A notification failure does not block the rest of the page", async () => {
  const { el, app } = setup({ [NOTIFICATIONS]: new Error("down"), [REQUESTS]: response(200, page([request()])) });
  await app.ready;
  assert.match(el("notification-status").textContent, /sẽ thử lại/);
  assert.ok(el("request-list").textContent.includes("Phiếu #9"));
});

test("Text in notifications is rendered as text", async () => {
  const body = "<img src=x onerror=alert(1)>";
  const { el, app } = setup({ [NOTIFICATIONS]: response(200, inbox([notification({ body })])) });
  await app.ready;
  assert.equal(el("notification-list").find(node => node.tag === "p").textContent, body);
});

// ---------- Validators ----------

test("Validators reject wrong tenant, status and shapes", () => {
  assert.equal(api.isDateChangeRequest(request(), 1), true);
  assert.equal(api.isDateChangeRequest(request(), 2), false);
  assert.equal(api.isDateChangeRequest(request({ status: "DONE" }), 1), false);
  assert.equal(api.isDateChangeRequest(request({ requestedCheckIn: "2030-12-10T00:00:00Z" }), 1), false);
  assert.equal(api.isDateChangeRequest(request({ decidedAt: "hôm qua" }), 1), false);
  assert.equal(api.isQuote(quote()), true);
  assert.equal(api.isQuote(quote({ available: "yes" })), false);
  assert.equal(api.isReschedulable(booking({ roomTypeName: "" })), false);
  assert.equal(api.isNotification(notification({ readAt: 5 })), false);
  assert.equal(api.isPage({ items: [] }), false);
});
