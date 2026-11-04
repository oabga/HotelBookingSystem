const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

for (const [port, app] of [[3000, "app1"], [3001, "app2"], [3002, "app3"]]) {
  http.createServer((request, response) => {
    const pathname = new URL(request.url, "http://localhost").pathname;
    const file = pathname === "/" || pathname === "/callback/"
      ? path.join(__dirname, app, "index.html")
      : ["/auth.js", "/api.js", "/bookings.js", "/catalog.js", "/admin.js", "/app.js", "/support.js", "/styles.css"].includes(pathname)
        ? path.join(__dirname, pathname.slice(1))
        : null;

    if (!file) {
      response.writeHead(404);
      response.end();
      return;
    }

    try {
      const contents = fs.readFileSync(file);
      response.writeHead(200, {
        "Content-Type": pathname.endsWith(".js") ? "text/javascript; charset=utf-8"
          : pathname.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8",
        "Cache-Control": "no-store"
      });
      response.end(contents);
    } catch (_error) {
      response.writeHead(500);
      response.end();
    }
  }).listen(port, "localhost", () => {
    console.log(app + ": http://localhost:" + port + "/ (callback: /callback/)");
  });
}
