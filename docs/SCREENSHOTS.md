# RackWatch screenshots

Screenshots are captured from the real running dashboard by `scripts/capture_screenshots.mjs`.

From the project root, run:

```bash
npm install --save-dev playwright
npx playwright install chromium
node scripts/capture_screenshots.mjs
```

The script reads `RACKWATCH_API_KEY` from `.env` and writes 11 PNG files into this directory. The Docker Compose stack must be running. For useful chart captures, the daemon should be sending readings. The script does not submit alert acknowledgements.

Expected captures:

1. `01-api-key-entry.png` — API-key entry.
2. `02-dashboard-full.png` — full dashboard.
3. `03-rooms-overview.png` — room overview.
4. `04-room-a-details.png` — first room card.
5. `05-room-b-details.png` — second room card.
6. `06-open-alerts.png` — open alerts.
7. `07-alert-acknowledgement.png` — acknowledgement control with a demo name typed, not submitted.
8. `08-temperature-chart.png` — temperature chart.
9. `09-humidity-chart.png` — humidity chart.
10. `10-excel-report.png` — report controls.
11. `11-mobile-dashboard.png` — narrow viewport.

No fabricated placeholder screenshots are included.
