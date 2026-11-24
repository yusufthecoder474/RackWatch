#include "reading.h"

#include <string.h>

int format_ts_utc(time_t t, char *buf, size_t len) {
    struct tm tm_utc;
    if (buf == NULL || len < 21) {
        return -1;
    }
    if (gmtime_r(&t, &tm_utc) == NULL) {
        return -1;
    }
    if (strftime(buf, len, "%Y-%m-%dT%H:%M:%SZ", &tm_utc) == 0) {
        return -1;
    }
    return 0;
}

const char *kind_name(SensorKind kind) {
    return kind == KIND_TEMP ? "temp" : "humidity";
}

const char *mode_name(SimMode mode) {
    switch (mode) {
        case MODE_NORMAL:
            return "normal";
        case MODE_COOLING_FAILURE:
            return "cooling_failure";
        case MODE_STUCK:
            return "stuck";
        default:
            return "unknown";
    }
}

int parse_kind(const char *s, SensorKind *out) {
    if (s == NULL || out == NULL) {
        return -1;
    }
    if (strcmp(s, "temp") == 0) {
        *out = KIND_TEMP;
        return 0;
    }
    if (strcmp(s, "humidity") == 0) {
        *out = KIND_HUMIDITY;
        return 0;
    }
    return -1;
}

int parse_mode(const char *s, SimMode *out) {
    if (s == NULL || out == NULL) {
        return -1;
    }
    if (strcmp(s, "normal") == 0) {
        *out = MODE_NORMAL;
        return 0;
    }
    if (strcmp(s, "cooling_failure") == 0) {
        *out = MODE_COOLING_FAILURE;
        return 0;
    }
    if (strcmp(s, "stuck") == 0) {
        *out = MODE_STUCK;
        return 0;
    }
    return -1;
}
