const test = require("node:test");
const assert = require("node:assert/strict");
const { parseFragment, decodeClaims, initAuth } = require("./auth.js");

function fakePage(appId, hash = "", pathname = "/callback/") {
  const elements = new Map();
  for (const id of ["login-button", "login-status", "integration-status", "user-email", "user-role", "user-tenant"]) {
    elements.set(id, { textContent: "", disabled: true, addEventListener(_event, callback) { this.click = callback; } });
  }
  const location = { hash, pathname, search: "", assigned: null, assign(url) { this.assigned = url; } };
  const history = { state: null, replaced: null, replaceState(_state, _title, url) { this.replaced = url; location.hash = ""; } };
  const win = { location, history };
  const doc = { body: { dataset: { appId } }, getElementById(id) { return elements.get(id); } };
  return { win, doc, elements };
}

function sampleToken(role = "USER", tenantId = 1) {
  const payload = { email: "sample@example.test", role, tenant_id: tenantId };
  return "e30." + Buffer.from(JSON.stringify(payload)).toString("base64url") + ".c2ln";
}

test("Login button navigates to the correct IAM URL for each app", () => {
  for (const appId of ["app1", "app2"]) {
    const page = fakePage(appId, "", "/");
    const auth = initAuth(page.win, page.doc);
    assert.equal(page.elements.get("login-button").disabled, false);
    page.elements.get("login-button").click();
    assert.equal(page.win.location.assigned, "http://localhost:8084/auth/start?app=" + appId);
    assert.deepEqual(auth.bearerHeaders(), {});
    assert.equal(auth.getDisplayRole(), null);
    assert.equal(auth.getDisplayTenantId(), null);
  }
});

test("Token fragment is removed and decoded claims are display only", () => {
  const token = sampleToken();
  const page = fakePage("app1", "#token=" + token);
  const auth = initAuth(page.win, page.doc);
  assert.equal(page.win.history.replaced, "/callback/");
  assert.equal(page.win.location.hash, "");
  assert.equal(page.elements.get("user-email").textContent, "sample@example.test");
  assert.equal(page.elements.get("user-role").textContent, "USER");
  assert.equal(page.elements.get("user-tenant").textContent, "1");
  assert.match(page.elements.get("login-status").textContent, /chưa xác minh chữ ký/);
  assert.deepEqual(auth.bearerHeaders(), { Authorization: "Bearer " + token });
  assert.equal(auth.getDisplayRole(), "USER");
  assert.equal(auth.getDisplayTenantId(), 1);
});

test("ADMIN display role comes from the token on either app", () => {
  for (const appId of ["app1", "app2"]) {
    const page = fakePage(appId, "#token=" + sampleToken("ADMIN"));
    const auth = initAuth(page.win, page.doc);
    assert.equal(auth.getDisplayRole(), "ADMIN");
    assert.equal(auth.getDisplayTenantId(), 1);
    assert.equal(page.elements.get("user-role").textContent, "ADMIN");
  }
});

test("Non-numeric, non-positive and fractional tenant claims are rejected", () => {
  for (const tenantId of ["1", 0, -1, 1.5]) {
    const page = fakePage("app1", "#token=" + sampleToken("USER", tenantId));
    const auth = initAuth(page.win, page.doc);
    assert.equal(page.win.location.hash, "");
    assert.match(page.elements.get("login-status").textContent, /không hợp lệ/);
    assert.deepEqual(auth.bearerHeaders(), {});
    assert.equal(auth.getDisplayRole(), null);
    assert.equal(auth.getDisplayTenantId(), null);
  }
});

test("Unknown role is rejected before retaining JWT", () => {
  const page = fakePage("app2", "#token=" + sampleToken("OWNER", 1));
  const auth = initAuth(page.win, page.doc);
  assert.match(page.elements.get("login-status").textContent, /không hợp lệ/);
  assert.deepEqual(auth.bearerHeaders(), {});
  assert.equal(auth.getDisplayRole(), null);
});

test("Known error fragments are removed and never create a bearer header", () => {
  for (const [error, message] of [["not_provisioned", /chưa được IAM cấp quyền/], ["login_failed", /thất bại/]]) {
    const page = fakePage("app2", "#error=" + error);
    const auth = initAuth(page.win, page.doc);
    assert.equal(page.win.history.replaced, "/callback/");
    assert.equal(page.win.location.hash, "");
    assert.match(page.elements.get("login-status").textContent, message);
    assert.deepEqual(auth.bearerHeaders(), {});
    assert.equal(auth.getDisplayRole(), null);
    assert.equal(auth.getDisplayTenantId(), null);
  }
});

test("Malformed token is cleared and not retained", () => {
  const page = fakePage("app1", "#token=not-a-jwt");
  const auth = initAuth(page.win, page.doc);
  assert.equal(page.win.history.replaced, "/callback/");
  assert.match(page.elements.get("login-status").textContent, /không hợp lệ/);
  assert.deepEqual(auth.bearerHeaders(), {});
  assert.equal(auth.getDisplayRole(), null);
  assert.equal(auth.getDisplayTenantId(), null);
  assert.throws(() => decodeClaims("not-a-jwt"));
  assert.deepEqual(parseFragment("#error=login_failed"), { kind: "error", value: "login_failed" });
});
