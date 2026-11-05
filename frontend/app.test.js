const test = require("node:test");
const assert = require("node:assert/strict");
const api = require("./api.js");
const { initApp } = require("./app.js");
const { FakeDocument, response, fakeFetch, fakeAuth, fixedNow, sampleHotel, sampleBooking } = require("./test-support.js");

function routes(extra = {}) {
  return { "GET /api/hotels": response(200, [sampleHotel]), "GET /api/bookings": response(200, [sampleBooking()]), ...extra };
}

test("Signed-out page makes no API call and hides ADMIN tools", () => {
  const doc = new FakeDocument();
  const fetchFn = fakeFetch({});
  assert.equal(initApp(doc, fakeAuth(null), fetchFn, fixedNow), null);
  assert.equal(fetchFn.calls.length, 0);
  assert.equal(doc.getElementById("admin-section").hidden, true);
});

test("USER loads hotels and bookings but not ADMIN tools or a search", async () => {
  const doc = new FakeDocument();
  const fetchFn = fakeFetch(routes());
  const app = initApp(doc, fakeAuth("USER"), fetchFn, fixedNow);
  await app.ready;
  assert.equal(doc.getElementById("admin-section").hidden, true);
  assert.equal(app.admin, null);
  assert.equal(doc.getElementById("bookings-title").textContent, "Booking của tôi");
  assert.deepEqual(fetchFn.calls.map(call => call.key).sort(), ["GET /api/bookings", "GET /api/hotels"]);
});

test("App 1 blocks ADMIN while App Admin opens tools for ADMIN", async () => {
  const userPortal = new FakeDocument("app1");
  const userPortalFetch = fakeFetch(routes());
  assert.equal(initApp(userPortal, fakeAuth("ADMIN"), userPortalFetch, fixedNow), null);
  assert.equal(userPortalFetch.calls.length, 0);

  const adminPortal = new FakeDocument("app2");
  const app = initApp(adminPortal, fakeAuth("ADMIN"), fakeFetch(routes()), fixedNow);
  await app.ready;
  assert.equal(adminPortal.getElementById("admin-section").hidden, false);
  assert.equal(adminPortal.getElementById("bookings-title").textContent, "Booking trong tenant");
});

test("A 401 from any API tells the user to log in again", async () => {
  const doc = new FakeDocument();
  const app = initApp(doc, fakeAuth("USER"), fakeFetch(routes({ "GET /api/bookings": response(401) })), fixedNow);
  await app.ready;
  assert.equal(doc.getElementById("login-status").textContent, api.sessionExpired);
  assert.equal(doc.getElementById("bookings-status").textContent, api.sessionExpired);
});

test("Frontend no longer calls any v2.0 route", () => {
  const fs = require("node:fs");
  const path = require("node:path");
  for (const file of ["api.js", "bookings.js", "catalog.js", "admin.js", "app.js", "auth.js"]) {
    const source = fs.readFileSync(path.join(__dirname, file), "utf8");
    assert.doesNotMatch(source, /\/rooms\b|roomId\b|localStorage|sessionStorage/, file);
  }
});
