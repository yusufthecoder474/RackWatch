#!/usr/bin/env node
/** Capture real RackWatch dashboard screenshots using Playwright. */
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

function readEnv(file) {
  if (!fs.existsSync(file)) return {};
  const result = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const s = line.trim();
    if (!s || s.startsWith('#')) continue;
    const i = s.indexOf('=');
    if (i < 1) continue;
    let value = s.slice(i + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    result[s.slice(0, i).trim()] = value;
  }
  return result;
}

const root = process.cwd();
const env = { ...readEnv(path.join(root, '.env')), ...process.env };
const key = env.RACKWATCH_API_KEY;
const base = (env.RACKWATCH_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const out = path.join(root, 'docs', 'screenshots');
if (!key) throw new Error('RACKWATCH_API_KEY is missing. Add it to the project .env file.');
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });
const shot = async (name, locator = null, fullPage = false) => {
  const target = path.join(out, name);
  if (locator && await locator.count()) {
    await locator.first().scrollIntoViewIfNeeded().catch(() => {});
    await locator.first().screenshot({ path: target, animations: 'disabled' });
  } else {
    await page.screenshot({ path: target, fullPage, animations: 'disabled' });
  }
  console.log(`Saved ${path.relative(root, target)}`);
};

try {
  const health = await page.request.get(`${base}/actuator/health`);
  if (!health.ok()) throw new Error(`Health check failed: HTTP ${health.status()}`);
  await page.goto(`${base}/`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.locator('#key-form').waitFor({ state: 'visible', timeout: 15000 });
  await shot('01-api-key-entry.png', null, true);
  await page.locator('#api-key-input').fill(key);
  await page.locator('#key-form').locator('button[type="submit"], input[type="submit"]').first().click();
  await page.locator('#rooms-section').waitFor({ state: 'visible', timeout: 15000 });
  await page.waitForTimeout(1500);
  await shot('02-dashboard-full.png', null, true);
  await shot('03-rooms-overview.png', page.locator('#rooms-section'));
  const cards = page.locator('#rooms .room-card');
  if (await cards.count() > 0) {
    await shot('04-room-a-details.png', cards.nth(0));
  } else {
    await shot('04-room-a-details.png', page.locator('#rooms-section'));
  }
  if (await cards.count() > 1) {
    await shot('05-room-b-details.png', cards.nth(1));
  } else {
    await shot('05-room-b-details.png', page.locator('#rooms-section'));
  }
  await shot('06-open-alerts.png', null, true);
  const alert = page.locator('#alerts .alert-row').first();
  if (await alert.count()) {
    const name = alert.locator('input[type="text"]');
    if (await name.count()) await name.fill('Demo Reviewer');
    await shot('07-alert-acknowledgement.png', null, true);
  } else await shot('07-alert-acknowledgement.png', null, true);

  const selector = page.locator('#sensor-select');
  const getOptions = async () => selector.locator('option').evaluateAll(els => els.map(e => ({ value: e.value, text: e.textContent || '' })));
  if (await selector.count()) {
    const opts = await getOptions();
    const temp = opts.find(o => /TEMP/i.test(o.text));
    if (temp) await selector.selectOption(temp.value);
    await page.waitForTimeout(500);
  }
  await shot('08-temperature-chart.png', page.locator('#chart-section'));
  if (await selector.count()) {
    const opts = await getOptions();
    const rh = opts.find(o => /HUMIDITY|RH/i.test(o.text));
    if (rh) { await selector.selectOption(rh.value); await page.waitForTimeout(500); }
  }
  await shot('09-humidity-chart.png', page.locator('#chart-section'));
  await shot('10-excel-report.png', page.locator('#report-section'));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await shot('11-mobile-dashboard.png', null, true);
  console.log(`\nCapture complete: ${out}`);
  console.log('Review screenshots before committing. For useful charts, make sure the daemon has sent readings.');
} finally {
  await browser.close();
}
