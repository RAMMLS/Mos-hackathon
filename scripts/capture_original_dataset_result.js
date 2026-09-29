const path = require("path");
const { chromium } = require(path.join(
  process.env.TEMP,
  "codex-playwright",
  "node_modules",
  "playwright-core"
));

const root = path.resolve(__dirname, "..");
const inputPath = path.join(
  root,
  "data",
  "tz_update_2026_09_19",
  "corrected_dataset.geojson"
);
const resultPath = path.join(
  root,
  "results",
  "full-clearance-2026-09-28",
  "portfolio-any-boundary-b3-first",
  "result.geojson"
);
const outputPath = path.join(
  root,
  "docs",
  "images",
  "original-dataset-portfolio-17-of-17.png"
);

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  });
  const context = await browser.newContext({
    viewport: { width: 1920, height: 1200 },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:8080/viewer/index.html", {
    waitUntil: "domcontentloaded",
    timeout: 15_000,
  });
  await page.locator("#input-file").waitFor({ state: "attached", timeout: 10_000 });
  await page.waitForFunction(() => {
    const tone = document.querySelector("#run-state")?.dataset?.tone;
    return tone === "ready" || tone === "error";
  }, null, { timeout: 15_000 });
  await page.locator("#input-file").setInputFiles(inputPath);
  await page.waitForFunction(() => {
    const text = document.querySelector("#scene-title")?.textContent || "";
    return text.includes("corrected_dataset.geojson");
  });
  await page.locator("#result-file").setInputFiles(resultPath);
  await page.waitForFunction(() =>
    document.querySelector("#metric-oks")?.textContent?.includes("17 / 17")
  );
  await page.locator("#fit-map").click();
  await page.waitForTimeout(500);
  await page.locator(".map-stage").screenshot({ path: outputPath });
  console.log(JSON.stringify({
    outputPath,
    title: await page.locator("#scene-title").textContent(),
    connected: await page.locator("#metric-oks").textContent(),
    status: await page.locator("#metric-status strong").textContent(),
    ruleset: await page.locator("#metric-ruleset strong").textContent(),
  }));
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
