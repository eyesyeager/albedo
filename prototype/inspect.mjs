import { chromium } from "playwright";
const b = await chromium.launch();
const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
const p = await ctx.newPage();
p.on("console", (m) => { if (m.type() === "error") console.log("CONSOLE-ERR:", m.text()); });
p.on("pageerror", (e) => console.log("PAGEERR:", e.message));
await p.goto("http://localhost:5173/#/login", { waitUntil: "load" });
await new Promise((r) => setTimeout(r, 400));
await p.getByRole("button", { name: /登录并授权/ }).click();
await new Promise((r) => setTimeout(r, 900));
for (const h of ["/apps/app-1001", "/knowledge/kb-001", "/mcp/mc-001"]) {
  await p.goto(`http://localhost:5173/#${h}`, { waitUntil: "load" });
  await new Promise((r) => setTimeout(r, 1000));
  const info = await p.evaluate(() => ({
    hash: location.hash,
    h1: (document.querySelector("h1") || {}).innerText,
    view: document.querySelector(".page") ? document.querySelector(".page").className : "NO-PAGE",
  }));
  console.log(h, "=>", JSON.stringify(info));
}
await b.close();
