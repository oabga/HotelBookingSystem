const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const api = require("./api.js");
const { initSupport } = require("./support.js");
const { FakeDocument, response, fakeFetch, fakeAuth, sampleBooking } = require("./test-support.js");

const LIST = "GET /api/support/tickets";
const DETAIL = "GET /api/support/tickets/9";
const CREATE = "POST /api/support/tickets";
const REPLY = "POST /api/support/tickets/9/messages";
const STATUS = "PATCH /api/support/tickets/9/status";

function ticket(overrides = {}) {
  return {
    id: 9, tenantId: 1, bookingId: 5, requesterEmail: "user@example.test",
    subject: "Cần hỗ trợ", description: "Đổi giờ đến", status: "OPEN",
    createdAt: "2026-11-04T05:00:00Z", updatedAt: "2026-11-04T05:00:00Z", closedAt: null,
    ...overrides
  };
}
const message = (overrides = {}) => ({
  id: 3, ticketId: 9, authorUserId: 11, authorRole: "USER", body: "Xin chào", createdAt: "2026-11-04T05:01:00Z", ...overrides
});
const page = (items, overrides = {}) => ({ items, page: 0, size: 10, totalElements: items.length, totalPages: items.length ? 1 : 0, ...overrides });
const detail = (overrides = {}) => ticket({ messages: [], ...overrides });

function setup(routes = {}, role = "USER") {
  const doc = new FakeDocument("app3");
  const fetchFn = fakeFetch({
    [LIST]: response(200, page([ticket()])),
    "GET /api/bookings": response(200, [sampleBooking()]),
    [DETAIL]: response(200, detail()),
    ...routes
  });
  const app = initSupport(doc, fakeAuth(role), fetchFn);
  const el = id => doc.getElementById(id);
  const fill = (subject = "Cần hỗ trợ", description = "Đổi giờ đến") => {
    el("ticket-subject").value = subject;
    el("ticket-description").value = description;
  };
  const open = async () => {
    await app.ready;
    await el("ticket-list").button("Xem").fire("click");
    await tick();
  };
  return { doc, el, fetchFn, app, fill, open };
}

const tick = () => new Promise(resolve => setImmediate(resolve));

// ---------- Khớp với HTML ----------

test("Every element id used by the script exists in the Support Center page", () => {
  const html = fs.readFileSync(path.join(__dirname, "app3/index.html"), "utf8");
  const scripts = ["support.js", "auth.js"].map(file => fs.readFileSync(path.join(__dirname, file), "utf8")).join("\n");
  const used = [...scripts.matchAll(/getElementById\("([^"]+)"\)/g)].map(match => match[1]);
  assert.ok(used.length > 15);
  for (const id of new Set(used)) assert.match(html, new RegExp('id="' + id + '"'), id);
});

test("Support Center page declares app3 and loads only its own scripts", () => {
  const html = fs.readFileSync(path.join(__dirname, "app3/index.html"), "utf8");
  assert.match(html, /data-app-id="app3"/);
  assert.doesNotMatch(html, /bookings\.js|catalog\.js|admin\.js|\/app\.js/);
});

test("Static server serves the Support Center on port 3002", () => {
  const server = fs.readFileSync(path.join(__dirname, "server.js"), "utf8");
  assert.match(server, /\[3002, "app3"\]/);
  assert.match(server, /"\/support\.js"/);
});

// ---------- Đăng nhập và role ----------

test("Signed-out page calls no API and hides the create form", () => {
  const doc = new FakeDocument("app3");
  const fetchFn = fakeFetch({});
  assert.equal(initSupport(doc, fakeAuth(null), fetchFn), null);
  assert.equal(fetchFn.calls.length, 0);
  assert.equal(doc.getElementById("create-panel").hidden, true);
});

test("USER gets the create form and own booking options", async () => {
  const { el, app } = setup();
  await app.ready;
  assert.equal(el("create-panel").hidden, false);
  assert.equal(el("tickets-title").textContent, "Ticket của tôi");
  assert.deepEqual(el("ticket-booking").children.map(option => option.value), ["", "5"]);
});

test("ADMIN has no create form and does not load bookings, and sees who sent each ticket", async () => {
  const { el, fetchFn, app } = setup({}, "ADMIN");
  await app.ready;
  assert.equal(el("create-panel").hidden, true);
  assert.equal(el("tickets-title").textContent, "Ticket trong tenant");
  assert.equal(fetchFn.called("GET /api/bookings").length, 0);
  assert.ok(el("ticket-list").textContent.includes("Người gửi: user@example.test"));
});

test("USER does not see requester line on own tickets", async () => {
  const { el, app } = setup();
  await app.ready;
  assert.ok(!el("ticket-list").textContent.includes("Người gửi"));
});

test("A failed booking list still allows a general ticket", async () => {
  const { el, app } = setup({ "GET /api/bookings": response(500) });
  await app.ready;
  assert.match(el("bookings-note").textContent, /vẫn có thể gửi/);
  assert.equal(el("create-submit").disabled, false);
});

test("A 401 tells the user to log in again", async () => {
  const { el, app } = setup({ [LIST]: response(401) });
  await app.ready;
  assert.equal(el("login-status").textContent, api.sessionExpired);
  assert.equal(el("tickets-status").textContent, api.sessionExpired);
});

// ---------- Danh sách ----------

test("List requests a page through the Gateway and renders tickets", async () => {
  const { el, fetchFn, app } = setup();
  await app.ready;
  assert.equal(fetchFn.called(LIST)[0].url, "http://localhost:8080/api/support/tickets?page=0&size=10");
  const text = el("ticket-list").textContent;
  for (const part of ["#9 · Cần hỗ trợ", "Mới", "Booking #5"]) assert.ok(text.includes(part), part);
  assert.equal(el("tickets-status").textContent, "Có 1 ticket.");
});

test("Empty list and general tickets are handled", async () => {
  const empty = setup({ [LIST]: response(200, page([])) });
  await empty.app.ready;
  assert.equal(empty.el("tickets-status").textContent, "Chưa có ticket.");
  const general = setup({ [LIST]: response(200, page([ticket({ bookingId: null })])) });
  await general.app.ready;
  assert.ok(general.el("ticket-list").textContent.includes("Không gắn booking"));
});

test("A ticket from another tenant blocks the whole list", async () => {
  const { el, app } = setup({ [LIST]: response(200, page([ticket(), ticket({ id: 10, tenantId: 2 })])) });
  await app.ready;
  assert.equal(el("ticket-list").children.length, 0);
  assert.match(el("tickets-status").textContent, /không hợp lệ/);
});

test("List errors have their own messages", async () => {
  for (const [route, pattern] of [[response(403), /chưa được cấp/], [response(500), /HTTP 500/], [new Error("down"), /Không kết nối/], [response(200), /không hợp lệ/]]) {
    const { el, app } = setup({ [LIST]: route });
    await app.ready;
    assert.match(el("tickets-status").textContent, pattern);
  }
});

test("Pager moves between pages and stops at the ends", async () => {
  const first = response(200, page([ticket()], { totalElements: 11, totalPages: 2 }));
  const second = response(200, page([ticket({ id: 10 })], { page: 1, totalElements: 11, totalPages: 2 }));
  const { el, fetchFn, app } = setup({ [LIST]: [first, second, first] });
  await app.ready;
  assert.equal(el("prev-page").disabled, true);
  assert.equal(el("next-page").disabled, false);
  assert.equal(el("page-info").textContent, "Trang 1/2");

  await el("next-page").fire("click");
  await tick();
  assert.match(fetchFn.called(LIST)[1].url, /page=1&size=10/);
  assert.equal(el("next-page").disabled, true);
  assert.equal(el("prev-page").disabled, false);

  await el("prev-page").fire("click");
  await tick();
  assert.match(fetchFn.called(LIST)[2].url, /page=0&size=10/);
});

test("Reload button fetches the list again", async () => {
  const { el, fetchFn, app } = setup();
  await app.ready;
  await el("tickets-reload").fire("click");
  await tick();
  assert.equal(fetchFn.called(LIST).length, 2);
});

// ---------- Tạo ticket ----------

test("Create sends only subject, description and the chosen booking", async () => {
  const { el, fetchFn, app, fill } = setup({ [CREATE]: response(201, ticket()) });
  await app.ready;
  fill("  Cần hỗ trợ ", " Đổi giờ đến\n");
  el("ticket-booking").value = "5";
  await el("create-form").fire("submit");
  assert.deepEqual(fetchFn.called(CREATE)[0].body, { subject: "Cần hỗ trợ", description: "Đổi giờ đến", bookingId: 5 });
  assert.match(el("create-status").textContent, /Đã tạo ticket #9/);
  assert.equal(el("ticket-subject").value, "");
  assert.equal(el("ticket-detail").hidden, false, "new ticket opens");
});

test("General ticket omits bookingId", async () => {
  const { el, fetchFn, app, fill } = setup({ [CREATE]: response(201, ticket({ bookingId: null })) });
  await app.ready;
  fill();
  await el("create-form").fire("submit");
  assert.deepEqual(fetchFn.called(CREATE)[0].body, { subject: "Cần hỗ trợ", description: "Đổi giờ đến" });
  assert.match(el("create-status").textContent, /Đã tạo/);
});

test("New ticket on a later page moves the list to that page", async () => {
  const full = response(200, page([ticket({ id: 1 })], { totalElements: 11, totalPages: 2 }));
  const last = response(200, page([ticket()], { page: 1, totalElements: 11, totalPages: 2 }));
  const { el, fetchFn, app, fill } = setup({
    [LIST]: [response(200, page([ticket({ id: 1 })], { totalElements: 10, totalPages: 1 })), full, last],
    [CREATE]: response(201, ticket({ bookingId: null }))
  });
  await app.ready;
  fill();
  await el("create-form").fire("submit");
  assert.match(fetchFn.called(LIST).at(-1).url, /page=1&size=10/);
});

test("Invalid ticket text never reaches the API", async () => {
  const cases = [
    ["", "d", /tiêu đề/i], ["s", "  ", /nội dung/i], ["a".repeat(201), "d", /200/], ["s", "a".repeat(4001), /4000/],
    ["dòng 1\ndòng 2", "d", /một dòng/], ["<b>x</b>", "d", /HTML/], ["s", "<script>", /HTML/], ["s", "a\u0000b", /ký tự/]
  ];
  for (const [subject, description, pattern] of cases) {
    const { el, fetchFn, app, fill } = setup();
    await app.ready;
    fill(subject, description);
    await el("create-form").fire("submit");
    assert.match(el("create-status").textContent, pattern, subject);
    assert.equal(fetchFn.called(CREATE).length, 0);
  }
});

test("Plain text with a less-than sign is allowed", async () => {
  const { el, fetchFn, app, fill } = setup({ [CREATE]: response(201, ticket({ bookingId: null, subject: "Giá < 500000" })) });
  await app.ready;
  fill("Giá < 500000", "a < 5");
  await el("create-form").fire("submit");
  assert.equal(fetchFn.called(CREATE).length, 1);
});

test("Create failures have specific messages and are not retried", async () => {
  const cases = [
    [response(400), /không hợp lệ/], [response(401), /hết hạn/], [response(403), /chưa được cấp/],
    [response(404), /Booking không tồn tại/], [response(500), /HTTP 500/]
  ];
  for (const [route, pattern] of cases) {
    const { el, fetchFn, app, fill } = setup({ [CREATE]: route });
    await app.ready;
    fill();
    await el("create-form").fire("submit");
    assert.match(el("create-status").textContent, pattern);
    assert.equal(fetchFn.called(CREATE).length, 1);
    assert.equal(el("ticket-subject").value, "Cần hỗ trợ", "input is kept");
  }
});

test("Unknown create outcome warns against sending twice", async () => {
  for (const route of [new Error("down"), response(201), response(201, { id: 9 })]) {
    const { el, app, fill } = setup({ [CREATE]: route });
    await app.ready;
    fill();
    await el("create-form").fire("submit");
    assert.match(el("create-status").textContent, /Tải lại danh sách trước khi gửi lại/);
  }
});

test("Double submit creates one ticket and everything is locked until the response", async () => {
  let release;
  const { el, fetchFn, app, fill } = setup({ [CREATE]: () => new Promise(resolve => { release = resolve; }) });
  await app.ready;
  fill();
  const first = el("create-form").fire("submit");
  await el("create-form").fire("submit");
  assert.equal(el("create-submit").disabled, true);
  assert.equal(el("tickets-reload").disabled, true);
  assert.equal(el("ticket-list").button("Xem").disabled, true);
  await tick();
  release(response(500));
  await first;
  assert.equal(fetchFn.called(CREATE).length, 1);
  assert.equal(el("create-submit").disabled, false);
});

// ---------- Chi tiết ----------

test("Detail shows description and messages as text, not HTML", async () => {
  const body = "<img src=x onerror=alert(1)>";
  const { el, open } = setup({ [DETAIL]: response(200, detail({ messages: [message({ body })] })) });
  await open();
  assert.equal(el("ticket-detail").hidden, false);
  assert.equal(el("detail-description").textContent, "Đổi giờ đến");
  const paragraph = el("message-list").find(node => node.tag === "p");
  assert.equal(paragraph.textContent, body);
  assert.equal(paragraph.innerHTML, undefined);
  assert.ok(el("message-list").textContent.includes("Khách hàng"));
});

test("Support replies are labelled and a ticket without messages says so", async () => {
  const replied = setup({ [DETAIL]: response(200, detail({ messages: [message({ authorRole: "ADMIN" })] })) });
  await replied.open();
  assert.ok(replied.el("message-list").textContent.includes("Bộ phận hỗ trợ"));
  const empty = setup();
  await empty.open();
  assert.equal(empty.el("detail-status").textContent, "Chưa có tin nhắn.");
});

test("Detail errors do not leave an old ticket on screen", async () => {
  const { el, open, app } = setup({ [DETAIL]: [response(200, detail({ messages: [message()] })), response(404)] });
  await open();
  await app.openTicket(9, true);
  assert.equal(el("detail-status").textContent, "Ticket không tồn tại hoặc không thuộc phạm vi truy cập.");
  assert.equal(el("message-list").children.length, 0);
  assert.equal(el("reply-form").hidden, true);
  assert.equal(el("detail-description").textContent, "");
});

test("Message belonging to another ticket makes the detail invalid", async () => {
  const { el, open } = setup({ [DETAIL]: response(200, detail({ messages: [message({ ticketId: 99 })] })) });
  await open();
  assert.match(el("detail-status").textContent, /không hợp lệ/);
});

test("A slow detail answer is ignored once another ticket is opened", async () => {
  let release;
  const { el, app } = setup({
    "GET /api/support/tickets/1": () => new Promise(resolve => { release = resolve; }),
    [DETAIL]: response(200, detail())
  });
  await app.ready;
  const slow = app.openTicket(1, true);
  await tick();
  await app.openTicket(9, true);
  release(response(200, detail({ id: 1, subject: "Ticket cũ" })));
  await slow;
  assert.ok(el("detail-title").textContent.startsWith("#9"));
});

// ---------- Trả lời ----------

test("Reply sends only the body and refreshes ticket and list", async () => {
  const { el, fetchFn, open } = setup({ [REPLY]: response(201, message()) });
  await open();
  el("reply-body").value = "  Tôi đến lúc 20 giờ. ";
  await el("reply-form").fire("submit");
  assert.deepEqual(fetchFn.called(REPLY)[0].body, { body: "Tôi đến lúc 20 giờ." });
  assert.equal(el("reply-status").textContent, "Đã gửi tin nhắn.");
  assert.equal(el("reply-body").value, "");
  assert.equal(fetchFn.called(DETAIL).length, 2);
  assert.equal(fetchFn.called(LIST).length, 2);
});

test("Invalid reply never reaches the API", async () => {
  for (const [value, pattern] of [["  ", /Nhập nội dung/], ["a".repeat(4001), /4000/], ["<b>x", /HTML/]]) {
    const { el, fetchFn, open } = setup();
    await open();
    el("reply-body").value = value;
    await el("reply-form").fire("submit");
    assert.match(el("reply-status").textContent, pattern);
    assert.equal(fetchFn.called(REPLY).length, 0);
  }
});

test("Reply failures keep the text and explain what happened", async () => {
  const cases = [
    [response(409), /đã đóng/], [response(404), /không tồn tại/], [response(403), /không có quyền/],
    [new Error("down"), /Không rõ tin nhắn/], [response(201, { id: 1 }), /chưa thể xác nhận/]
  ];
  for (const [route, pattern] of cases) {
    const { el, fetchFn, open } = setup({ [REPLY]: route });
    await open();
    el("reply-body").value = "Xin chào";
    await el("reply-form").fire("submit");
    assert.match(el("reply-status").textContent, pattern);
    assert.equal(el("reply-body").value, "Xin chào");
    assert.equal(fetchFn.called(REPLY).length, 1);
  }
});

test("Expired session on reply makes no further requests", async () => {
  const { el, fetchFn, open } = setup({ [REPLY]: response(401) });
  await open();
  const before = fetchFn.calls.length;
  el("reply-body").value = "x";
  await el("reply-form").fire("submit");
  assert.equal(el("reply-status").textContent, api.sessionExpired);
  assert.equal(fetchFn.calls.length, before + 1);
});

test("Double submit sends one reply", async () => {
  let release;
  const { el, fetchFn, open } = setup({ [REPLY]: () => new Promise(resolve => { release = resolve; }) });
  await open();
  el("reply-body").value = "x";
  const first = el("reply-form").fire("submit");
  await el("reply-form").fire("submit");
  assert.equal(el("reply-submit").disabled, true);
  await tick();
  release(response(201, message()));
  await first;
  assert.equal(fetchFn.called(REPLY).length, 1);
});

test("Closed ticket hides the reply form and status action", async () => {
  const closed = detail({ status: "CLOSED", closedAt: "2026-11-04T06:00:00Z" });
  const { el, open } = setup({ [DETAIL]: response(200, closed), [LIST]: response(200, page([ticket({ status: "CLOSED", closedAt: "2026-11-04T06:00:00Z" })])) }, "ADMIN");
  await open();
  assert.equal(el("reply-form").hidden, true);
  assert.equal(el("status-actions").hidden, true);
  assert.match(el("detail-status").textContent, /đã đóng/);
  assert.ok(el("detail-meta").textContent.includes("Đóng lúc"));
});

// ---------- Trạng thái ----------

test("USER never sees the status action", async () => {
  const { el, open } = setup();
  await open();
  assert.equal(el("status-actions").hidden, true);
});

test("ADMIN only gets the next valid step for each status", async () => {
  for (const [status, label] of [["OPEN", "Bắt đầu xử lý"], ["IN_PROGRESS", "Đánh dấu đã giải quyết"], ["RESOLVED", "Đóng ticket"]]) {
    const { el, open } = setup({ [DETAIL]: response(200, detail({ status })) }, "ADMIN");
    await open();
    assert.equal(el("status-actions").hidden, false);
    assert.equal(el("status-button").textContent, label);
  }
});

test("Advancing status patches the next value and refreshes", async () => {
  const { el, fetchFn, open } = setup({
    [STATUS]: response(200, ticket({ status: "IN_PROGRESS" })),
    [DETAIL]: [response(200, detail()), response(200, detail({ status: "IN_PROGRESS" }))]
  }, "ADMIN");
  await open();
  await el("status-button").fire("click");
  await tick();
  assert.deepEqual(fetchFn.called(STATUS)[0].body, { status: "IN_PROGRESS" });
  assert.match(el("reply-status").textContent, /Đang xử lý/);
  assert.equal(el("status-button").textContent, "Đánh dấu đã giải quyết");
});

test("Status conflict explains and shows the current state", async () => {
  const { el, open } = setup({
    [STATUS]: response(409),
    [DETAIL]: [response(200, detail()), response(200, detail({ status: "RESOLVED" }))]
  }, "ADMIN");
  await open();
  await el("status-button").fire("click");
  await tick();
  assert.match(el("reply-status").textContent, /vừa thay đổi/);
  assert.equal(el("status-button").textContent, "Đóng ticket");
});

test("Unconfirmed status response is not reported as saved", async () => {
  const { el, open } = setup({ [STATUS]: response(200, ticket({ status: "OPEN" })) }, "ADMIN");
  await open();
  await el("status-button").fire("click");
  await tick();
  assert.match(el("reply-status").textContent, /chưa thể xác nhận/);
});

test("Double click changes status once and locks replies", async () => {
  let release;
  const { el, fetchFn, open } = setup({ [STATUS]: () => new Promise(resolve => { release = resolve; }) }, "ADMIN");
  await open();
  el("status-button").fire("click");
  await el("status-button").fire("click");
  assert.equal(el("status-button").disabled, true);
  assert.equal(el("reply-submit").disabled, true);
  await tick();
  release(response(500));
  await tick();
  assert.equal(fetchFn.called(STATUS).length, 1);
  assert.equal(el("status-button").disabled, false);
});

// ---------- API client ----------

test("Client supports PATCH with a JSON body", async () => {
  const fetchFn = fakeFetch({ "PATCH /api/x": response(200, {}) });
  await api.createClient({ fetchFn, auth: fakeAuth() }).patch("/x", { a: 1 });
  assert.equal(fetchFn.calls[0].init.method, "PATCH");
  assert.deepEqual(fetchFn.calls[0].body, { a: 1 });
});

test("Ticket validators reject wrong tenant, status and shapes", () => {
  assert.equal(api.isTicket(ticket(), 1), true);
  assert.equal(api.isTicket(ticket(), 2), false);
  assert.equal(api.isTicket(ticket({ status: "DONE" }), 1), false);
  assert.equal(api.isTicket(ticket({ bookingId: "5" }), 1), false);
  assert.equal(api.isTicket(ticket({ closedAt: "hôm qua" }), 1), false);
  assert.equal(api.isMessage(message({ authorRole: "ROOT" })), false);
  assert.equal(api.isTicketPage({ items: [] }), false);
});
