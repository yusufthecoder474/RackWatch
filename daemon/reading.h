#ifndef RACKWATCH_READING_H
#define RACKWATCH_READING_H

#include <stddef.h>
#include <time.h>

typedef enum {
    KIND_TEMP,
    KIND_HUMIDITY
} SensorKind;

typedef enum {
    MODE_NORMAL,
    MODE_COOLING_FAILURE,
    MODE_STUCK
} SimMode;

/* One sampled reading. Exactly one of temp_c / rh_pct is valid, matching
 * the API rule that a reading carries exactly one value. */
typedef struct {
    char sensor_id[32];
    char ts[32]; /* ISO-8601 UTC, "2026-11-23T12:00:00Z" (20 chars + NUL) */
    double temp_c;
    double rh_pct;
    int has_temp;
    int has_rh;
} Reading;

/* Format a time_t as ISO-8601 UTC into buf. buf must hold at least 21
 * bytes. Returns 0 on success, -1 on failure. */
int format_ts_utc(time_t t, char *buf, size_t len);

const char *kind_name(SensorKind kind);
const char *mode_name(SimMode mode);

/* Parse "temp"/"humidity" and "normal"/"cooling_failure"/"stuck".
 * Returns 0 on success, -1 on unknown input. */
int parse_kind(const char *s, SensorKind *out);
int parse_mode(const char *s, SimMode *out);

#endif /* RACKWATCH_READING_H */
