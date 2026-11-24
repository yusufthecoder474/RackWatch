#!/usr/bin/env python3
"""Generate the three deterministic labelled RackWatch scenarios.

Re-running this script reproduces the CSVs byte-for-byte (seeded RNG,
random.Random(42)). The data is SIMULATED - no real sensors were involved.

Layout: one reading per minute, two sensors per scenario (ROOM-A-TEMP-01
and ROOM-A-RH-01). Columns: sensor_id,ts,temp_c,rh_pct,label.
A TEMP row carries temp_c only; a HUMIDITY row carries rh_pct only.
Labels: NORMAL, COOLING_FAILURE, SENSOR_FAULT. A blip is a single
out-of-range reading labelled NORMAL: it is noise, not an incident,
so an alert opened on it is a false alarm by the evaluation's metric.

Scenarios:
  (a) normal_day.csv — 1440 minutes (a full day). Temperature follows a
      daily curve (22 C +/- 1.5 C) with noise; five single-reading temp
      spikes to 28.5-29 C and two single-reading humidity blips (65 %RH
      and 38 %RH). No incident. Naive should fire on every blip; smart
      should fire on none.
  (b) cooling_failure.csv — 120 minutes. 30 normal minutes, then the
      temperature ramps +0.5 C per minute for 20 minutes (22.5 -> 32 C)
      and holds at 32 C with noise. The hold has noise on purpose: a
      genuinely hot room holding exactly steady would look like a stuck
      sensor to the flatline rule, so the plateau must vary.
  (c) stuck_sensor.csv — 120 minutes. 30 normal minutes, then the
      temperature sensor sticks at exactly 22.5 C (in range, so no
      threshold rule can see it) for 90 minutes. Only the flatline
      rule can catch it. Humidity keeps varying normally throughout.
"""

import math
import random
from datetime import datetime, timedelta, timezone

RNG = random.Random(42)


def iso(ts):
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


def write_csv(path, rows):
    """rows: list of (sensor_id, ts, temp_c, rh_pct, label)."""
    with open(path, "w", encoding="utf-8") as f:
        f.write("sensor_id,ts,temp_c,rh_pct,label\n")
        for sensor_id, ts, temp_c, rh_pct, label in rows:
            tc = "" if temp_c is None else f"{temp_c:.1f}"
            rh = "" if rh_pct is None else f"{rh_pct:.1f}"
            f.write(f"{sensor_id},{iso(ts)},{tc},{rh},{label}\n")


def normal_day(path):
    start = datetime(2026, 1, 15, 0, 0, tzinfo=timezone.utc)
    temp_blips = {197: 28.5, 522: 28.5, 725: 29.0, 1053: 28.5, 1310: 28.5}
    rh_blips = {400: 65.0, 900: 38.0}
    rows = []
    for i in range(1440):
        ts = start + timedelta(minutes=i)
        temp = 22.0 + 1.5 * math.sin(2 * math.pi * (i - 540) / 1440) + RNG.gauss(0, 0.3)
        rh = 50.0 + 3.0 * math.sin(2 * math.pi * (i - 480) / 1440) + RNG.gauss(0, 1.0)
        temp = temp_blips.get(i, round(temp, 1))
        rh = rh_blips.get(i, round(rh, 1))
        rows.append(("ROOM-A-TEMP-01", ts, temp, None, "NORMAL"))
        rows.append(("ROOM-A-RH-01", ts, None, rh, "NORMAL"))
    write_csv(path, rows)


def cooling_failure(path):
    start = datetime(2026, 1, 16, 10, 0, tzinfo=timezone.utc)
    rows = []
    for i in range(120):
        ts = start + timedelta(minutes=i)
        if i < 30:
            temp = 22.0 + RNG.gauss(0, 0.3)
            label = "NORMAL"
        elif i < 50:
            temp = 22.5 + 0.5 * (i - 30) + RNG.gauss(0, 0.1)
            label = "COOLING_FAILURE"
        else:
            temp = 32.0 + RNG.gauss(0, 0.2)
            label = "COOLING_FAILURE"
        rh = 50.0 + RNG.gauss(0, 1.0)
        rows.append(("ROOM-A-TEMP-01", ts, round(temp, 1), None, label))
        rows.append(("ROOM-A-RH-01", ts, None, round(rh, 1), "NORMAL"))
    write_csv(path, rows)


def stuck_sensor(path):
    start = datetime(2026, 1, 17, 10, 0, tzinfo=timezone.utc)
    rows = []
    for i in range(120):
        ts = start + timedelta(minutes=i)
        if i < 30:
            temp = 22.0 + RNG.gauss(0, 0.4)
            label = "NORMAL"
        else:
            temp = 22.5
            label = "SENSOR_FAULT"
        rh = 50.0 + RNG.gauss(0, 1.0)
        rows.append(("ROOM-A-TEMP-01", ts, round(temp, 1), None, label))
        rows.append(("ROOM-A-RH-01", ts, None, round(rh, 1), "NORMAL"))
    write_csv(path, rows)


if __name__ == "__main__":
    normal_day("normal_day.csv")
    cooling_failure("cooling_failure.csv")
    stuck_sensor("stuck_sensor.csv")
    print("Wrote normal_day.csv, cooling_failure.csv, stuck_sensor.csv")
