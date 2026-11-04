// DOM và fetch giả tối thiểu cho test. Không được server.js phục vụ.
class FakeElement {
  constructor(tag = "div") {
    this.tag = tag;
    this.children = [];
    this.listeners = {};
    this.dataset = {};
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.className = "";
    this.ownText = "";
  }
  get textContent() {
    return this.ownText + this.children.map(child => child.textContent).join(" ");
  }
  set textContent(value) {
    this.ownText = String(value);
    this.children = [];
  }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; this.ownText = ""; }
  addEventListener(type, handler) { (this.listeners[type] = this.listeners[type] || []).push(handler); }
  async fire(type) {
    const event = { prevented: false, preventDefault() { this.prevented = true; } };
    await Promise.all((this.listeners[type] || []).map(handler => handler(event)));
    return event;
  }
  find(predicate) {
    for (const child of this.children) {
      if (predicate(child)) return child;
      const nested = child.find(predicate);
      if (nested) return nested;
    }
    return null;
  }
  findAll(predicate, found = []) {
    for (const child of this.children) {
      if (predicate(child)) found.push(child);
      child.findAll(predicate, found);
    }
    return found;
  }
  button(label) { return this.find(node => node.tag === "button" && node.textContent === label); }
  buttons(label) { return this.findAll(node => node.tag === "button" && node.textContent === label); }
}

class FakeDocument {
  constructor(appId = "app1") {
    this.elements = new Map();
    this.body = { dataset: { appId } };
  }
  getElementById(id) {
    if (!this.elements.has(id)) this.elements.set(id, new FakeElement());
    return this.elements.get(id);
  }
  createElement(tag) { return new FakeElement(tag); }
}

function response(status, body) {
  return {
    status,
    ok: status >= 200 && status < 300,
    async json() {
      if (body === undefined) throw new Error("not JSON");
      return body;
    }
  };
}

// routes: { "GET /api/hotels": response(...) | [response...] | () => response | Error }
function fakeFetch(routes) {
  const calls = [];
  async function fetchFn(url, init) {
    const key = init.method + " " + url.replace("http://localhost:8080", "").split("?")[0];
    calls.push({ key, url, init, body: init.body === undefined ? undefined : JSON.parse(init.body) });
    let route = routes[key];
    if (Array.isArray(route)) route = route.length > 1 ? route.shift() : route[0];
    if (typeof route === "function") route = await route();
    if (route instanceof Error) throw route;
    if (!route) return response(404);
    return route;
  }
  fetchFn.calls = calls;
  fetchFn.called = key => calls.filter(call => call.key === key);
  return fetchFn;
}

function fakeAuth(role = "USER", tenantId = 1) {
  return {
    bearerHeaders: () => (role ? { Authorization: "Bearer test-jwt" } : {}),
    getDisplayRole: () => role,
    getDisplayTenantId: () => tenantId
  };
}

// Ngày cố định để test không hỏng khi ngày seed đã qua.
const fixedNow = () => Date.parse("2026-11-04T05:00:00Z");

const sampleHotel = { id: 1, tenantId: 1, name: "Demo Hotel A", address: "Hà Nội" };
const sampleRoom = { roomTypeId: 1, name: "Standard", capacity: 2, pricePerNight: 500000, nights: 2, totalPricePerRoom: 1000000, availableRooms: 3 };

function sampleBooking(overrides = {}) {
  return {
    id: 5, tenantId: 1, roomTypeId: 1, userEmail: "user@example.test",
    checkIn: "2026-11-24", checkOut: "2026-11-26", roomCount: 1, totalAmount: 1000000,
    status: "PENDING_PAYMENT", paymentExpiresAt: "2026-11-04T05:15:00.123456Z", createdAt: "2026-11-04T05:00:00.123456Z",
    ...overrides
  };
}

module.exports = { FakeDocument, FakeElement, response, fakeFetch, fakeAuth, fixedNow, sampleHotel, sampleRoom, sampleBooking };
