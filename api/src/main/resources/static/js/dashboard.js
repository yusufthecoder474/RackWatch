/* RackWatch dashboard — vanilla JS, no framework.
 *
 * All text the API returns is inserted with textContent only, never
 * innerHTML: alert messages, sensor ids and room names come from the
 * server, and building DOM from them with innerHTML would let a
 * crafted sensor id or alert message run as HTML in the browser.
 * The API key lives in sessionStorage (this tab only) and is sent as
 * the X-API-Key header on every data request; the page itself loads
 * without a key. Chart.js is vendored locally (js/chart.umd.min.js).
 *
 * AUTO-REFRESH: rooms, alerts and the chart reload every 5 s with
 * setInterval. The interval pauses while the tab is hidden (no point
 * polling a page nobody is looking at) and backs off on errors: after
 * a failed refresh the next attempt waits 10 s, then 20 s, 40 s, up
 * to a 60 s cap, and resets to 5 s on the next success. A refresh
 * never overlaps the previous one. */
"use strict";

const KEY_STORAGE = "rackwatchApiKey";
const MAX_CHART_POINTS = 1000;

// Auto-refresh: 5 s normally, doubling backoff to a 60 s cap.
const AUTO_REFRESH_MS = 5000;
const AUTO_REFRESH_MAX_BACKOFF_MS = 60000;

const statusEl = document.getElementById("status");
const keyForm = document.getElementById("key-form");
const keyInput = document.getElementById("api-key-input");
const keySavedEl = document.getElementById("key-saved");
const roomsSection = document.getElementById("rooms-section");
const alertsSection = document.getElementById("alerts-section");
const chartSection = document.getElementById("chart-section");
const reportSection = document.getElementById("report-section");
const roomsEl = document.getElementById("rooms");
const alertsEl = document.getElementById("alerts");
const sensorSelect = document.getElementById("sensor-select");
const windowSelect = document.getElementById("window-select");
const chartNoteEl = document.getElementById("chart-note");
const reportStatusEl = document.getElementById("report-status");

let chart = null;
let sensors = []; /* { id, kind } for the chart dropdown */
let refreshTimer = null; // the setInterval handle, or null when paused
let refreshBackoffMs = AUTO_REFRESH_MS; // delay before the next attempt
let refreshNotBefore = 0; // epoch ms: refreshes before this are skipped
let isRefreshing = false; // true while a refresh is in flight
// Names the user has typed into the per-alert ack boxes, keyed by
// alert id, so a re-render does not wipe a name mid-typing.
const ackNames = new Map();
// The last alerts payload (as JSON text). If the next poll returns
// the same alerts, the list is left alone — re-rendering would
// destroy focus and any name being typed for no visible change.
let lastAlertsJson = null;

function getKey() {
  return sessionStorage.getItem(KEY_STORAGE);
}

function setStatus(text, ok) {
  statusEl.textContent = text;
  statusEl.className = ok ? "ok" : "";
}

function setReportStatus(text, ok) {
  reportStatusEl.textContent = text;
  reportStatusEl.className = ok ? "ok" : "";
}

/* One helper for every data call: adds the key, and on a 401 the key
 * is wrong or missing, so the key form comes back and the stored key
 * is forgotten. On any other error the server's JSON message is shown. */
async function apiFetch(path, options) {
  const key = getKey();
  const response = await fetch(path, {
    ...(options || {}),
    headers: {
      ...(options && options.headers ? options.headers : {}),
      "X-API-Key": key || "",
    },
  });
  if (response.status === 401) {
    sessionStorage.removeItem(KEY_STORAGE);
    showKeyForm();
    throw new Error("API key rejected — please enter the key again.");
  }
  if (!response.ok) {
    let message = "Request failed: " + response.status;
    try {
      const body = await response.json();
      if (body && body.message) {
        message = body.message;
      }
    } catch (e) {
      /* not JSON: keep the status message */
    }
    throw new Error(message);
  }
  return response;
}

function showKeyForm() {
  keyForm.hidden = false;
  keySavedEl.hidden = true;
  roomsSection.hidden = true;
  alertsSection.hidden = true;
  chartSection.hidden = true;
  reportSection.hidden = true;
}

function showDashboard() {
  keyForm.hidden = true;
  keySavedEl.hidden = false;
  roomsSection.hidden = false;
  alertsSection.hidden = false;
  chartSection.hidden = false;
  reportSection.hidden = false;
}

/* Small helper: an element whose text is set with textContent. */
function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) {
    node.textContent = text;
  }
  if (className) {
    node.className = className;
  }
  return node;
}

function formatValue(sensor) {
  if (sensor.kind === "TEMP") {
    return sensor.temp_c === null || sensor.temp_c === undefined
      ? "no reading" : sensor.temp_c.toFixed(1) + " °C";
  }
  return sensor.rh_pct === null || sensor.rh_pct === undefined
    ? "no reading" : sensor.rh_pct.toFixed(1) + " %RH";
}

function formatTs(ts) {
  if (!ts) {
    return "—";
  }
  return new Date(ts).toLocaleString();
}

async function loadRooms() {
  const response = await apiFetch("/api/v1/rooms");
  const rooms = await response.json();
  while (roomsEl.firstChild) {
    roomsEl.removeChild(roomsEl.firstChild);
  }
  sensors = [];
  for (const room of rooms) {
    const card = el("div", null, "room-card " + room.status);
    const heading = el("h3", room.room_name + " ");
    heading.appendChild(el("span", room.status, "status"));
    card.appendChild(heading);
    for (const sensor of room.sensors) {
      sensors.push({ id: sensor.sensor_id, kind: sensor.kind });
      const line = el("p", null, "sensor-line");
      line.appendChild(el("span", sensor.sensor_id + " — "));
      line.appendChild(el("span", formatValue(sensor)));
      line.appendChild(el("span", " — " + formatTs(sensor.ts)));
      if (sensor.ts && !sensor.in_range) {
        line.appendChild(el("span", " — out of range", "out"));
      }
      card.appendChild(line);
    }
    roomsEl.appendChild(card);
  }
  fillSensorSelect();
}

function fillSensorSelect() {
  const previous = sensorSelect.value;
  while (sensorSelect.firstChild) {
    sensorSelect.removeChild(sensorSelect.firstChild);
  }
  for (const sensor of sensors) {
    const option = document.createElement("option");
    option.value = sensor.id;
    option.textContent = sensor.id + " (" + sensor.kind + ")";
    sensorSelect.appendChild(option);
  }
  if (previous && sensors.some((s) => s.id === previous)) {
    sensorSelect.value = previous;
  }
}

async function loadAlerts() {
  const response = await apiFetch("/api/v1/alerts?state=OPEN");
  const alerts = await response.json();
  const payload = JSON.stringify(alerts);
  // Unchanged payload: leave the list (and any name being typed)
  // exactly as it is.
  if (payload === lastAlertsJson) {
    return;
  }
  lastAlertsJson = payload;
  while (alertsEl.firstChild) {
    alertsEl.removeChild(alertsEl.firstChild);
  }
  if (alerts.length === 0) {
    alertsEl.appendChild(el("p", "No open alerts."));
    return;
  }
  for (const alert of alerts) {
    const row = el("div", null, "alert-row");
    const title = el("p");
    title.appendChild(el("span", alert.sensor_id + " — " + alert.rule + " — ", null));
    title.appendChild(el("span", alert.severity, "severity-" + alert.severity));
    row.appendChild(title);
    row.appendChild(el("p", alert.message));
    row.appendChild(el("p", "Opened " + formatTs(alert.opened_at)));
    const nameInput = document.createElement("input");
    nameInput.type = "text";
    nameInput.placeholder = "Your name";
    nameInput.setAttribute("aria-label", "Your name for acknowledgement");
    // Restore a name typed before the last re-render.
    if (ackNames.has(alert.id)) {
      nameInput.value = ackNames.get(alert.id);
    }
    nameInput.addEventListener("input", () => {
      ackNames.set(alert.id, nameInput.value);
    });
    const ackButton = el("button", "Acknowledge");
    ackButton.type = "button";
    ackButton.addEventListener("click", async () => {
      const name = nameInput.value.trim();
      if (!name) {
        setStatus("Enter your name to acknowledge an alert.");
        return;
      }
      try {
        await apiFetch("/api/v1/alerts/" + alert.id + "/ack", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ acked_by: name }),
        });
        ackNames.delete(alert.id);
        setStatus("Alert acknowledged.", true);
        await loadAlerts();
      } catch (err) {
        setStatus(err.message);
      }
    });
    row.appendChild(nameInput);
    row.appendChild(ackButton);
    alertsEl.appendChild(row);
  }
}

async function loadChart() {
  const sensorId = sensorSelect.value;
  if (!sensorId) {
    return;
  }
  const sensor = sensors.find((s) => s.id === sensorId);
  const hours = Number(windowSelect.value);
  const to = new Date();
  const from = new Date(to.getTime() - hours * 60 * 60 * 1000);
  const params = new URLSearchParams({
    sensorId,
    from: from.toISOString(),
    to: to.toISOString(),
  });
  const response = await apiFetch("/api/v1/readings?" + params.toString());
  const readings = await response.json();
  const isTemp = sensor.kind === "TEMP";
  const labels = readings.map((r) => new Date(r.ts).toLocaleTimeString());
  const data = readings.map((r) => (isTemp ? r.temp_c : r.rh_pct));
  if (chart) {
    chart.destroy();
    chart = null;
  }
  const ctx = document.getElementById("chart").getContext("2d");
  chart = new Chart(ctx, {
    type: "line",
    data: {
      labels,
      datasets: [{
        label: sensorId + (isTemp ? " (°C)" : " (%RH)"),
        data,
        borderColor: "#1565c0",
        tension: 0.1,
        pointRadius: 0,
      }],
    },
    options: {
      responsive: true,
      scales: {
        y: {
          title: { display: true, text: isTemp ? "Temperature (°C)" : "Humidity (%RH)" },
        },
      },
    },
  });
  chartNoteEl.textContent = readings.length >= MAX_CHART_POINTS
    ? "Showing the most recent " + MAX_CHART_POINTS + " readings in the range."
    : readings.length + " readings in the range.";
}

async function downloadReport() {
  const fromInput = document.getElementById("report-from");
  const toInput = document.getElementById("report-to");
  setReportStatus("");
  if (!fromInput.value || !toInput.value) {
    setReportStatus("Choose both a from and a to date.");
    return;
  }
  const from = new Date(fromInput.value);
  const to = new Date(toInput.value);
  if (to <= from) {
    setReportStatus("The to date must be after the from date.");
    return;
  }
  const params = new URLSearchParams({
    from: from.toISOString(),
    to: to.toISOString(),
  });
  try {
    /* fetch + blob, not a plain link: a link cannot send the X-API-Key
     * header, and the report endpoint requires the key. */
    const response = await apiFetch("/api/v1/reports/readings.xlsx?" + params.toString());
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "rackwatch-readings.xlsx";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    setReportStatus("Report downloaded.", true);
  } catch (err) {
    setReportStatus(err.message);
  }
}

/* The real load, which throws on the first failure so the caller
 * can decide about backoff. */
async function loadAll() {
  await loadRooms();
  await loadAlerts();
  await loadChart();
}

/* One refresh: reload everything, then reset (success) or double
 * (failure) the backoff. Returns true on success. */
async function refreshAll() {
  if (isRefreshing) {
    return false;
  }
  isRefreshing = true;
  try {
    await loadAll();
    refreshBackoffMs = AUTO_REFRESH_MS;
    refreshNotBefore = 0;
    setStatus("Loaded.", true);
    return true;
  } catch (err) {
    setStatus(err.message);
    refreshBackoffMs = Math.min(refreshBackoffMs * 2, AUTO_REFRESH_MAX_BACKOFF_MS);
    refreshNotBefore = Date.now() + refreshBackoffMs;
    return false;
  } finally {
    isRefreshing = false;
  }
}

/* Start the 5 s interval. The callback skips a tick while the tab is
 * hidden, while no key is stored, while a refresh is already running,
 * and while the error backoff says "not yet" — so a failing server
 * is not hammered every 5 s. */
function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(() => {
    if (document.hidden || !getKey() || isRefreshing) {
      return;
    }
    if (Date.now() < refreshNotBefore) {
      return;
    }
    refreshAll();
  }, AUTO_REFRESH_MS);
}

function stopAutoRefresh() {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

/* Pause while hidden, resume (with an immediate refresh) on return. */
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    stopAutoRefresh();
  } else if (getKey()) {
    startAutoRefresh();
    refreshAll();
  }
});

keyForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const key = keyInput.value.trim();
  if (!key) {
    setStatus("Enter the API key.");
    return;
  }
  sessionStorage.setItem(KEY_STORAGE, key);
  keyInput.value = "";
  showDashboard();
  await refreshAll();
  startAutoRefresh();
});

document.getElementById("key-change").addEventListener("click", () => {
  stopAutoRefresh();
  showKeyForm();
  keyInput.focus();
});

document.getElementById("key-clear").addEventListener("click", () => {
  stopAutoRefresh();
  sessionStorage.removeItem(KEY_STORAGE);
  lastAlertsJson = null;
  ackNames.clear();
  showKeyForm();
  setStatus("Key forgotten.");
});

document.getElementById("refresh-rooms").addEventListener("click", refreshAll);
document.getElementById("refresh-chart").addEventListener("click", async () => {
  try {
    await loadChart();
  } catch (err) {
    setStatus(err.message);
  }
});
sensorSelect.addEventListener("change", async () => {
  try {
    await loadChart();
  } catch (err) {
    setStatus(err.message);
  }
});
windowSelect.addEventListener("change", async () => {
  try {
    await loadChart();
  } catch (err) {
    setStatus(err.message);
  }
});
document.getElementById("download-report").addEventListener("click", downloadReport);

if (getKey()) {
  showDashboard();
  refreshAll().then(() => {
    startAutoRefresh();
  });
} else {
  showKeyForm();
}
