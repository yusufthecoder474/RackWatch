#include "json.h"

#include <stdio.h>

int json_reading(const Reading *r, char *buf, size_t len) {
    int n;
    if (r == NULL || buf == NULL || len == 0) {
        return -1;
    }
    /* Exactly one value per reading: temp_c XOR rh_pct. The API
     * rejects a reading with both or neither, so never emit one. */
    if (r->has_temp == r->has_rh) {
        return -1;
    }
    if (r->has_temp) {
        n = snprintf(buf, len,
                     "{\"sensor_id\":\"%s\",\"ts\":\"%s\",\"temp_c\":%.1f}",
                     r->sensor_id, r->ts, r->temp_c);
    } else {
        n = snprintf(buf, len,
                     "{\"sensor_id\":\"%s\",\"ts\":\"%s\",\"rh_pct\":%.1f}",
                     r->sensor_id, r->ts, r->rh_pct);
    }
    if (n < 0 || (size_t)n >= len) {
        return -1;
    }
    return 0;
}

int json_batch(const Reading *readings, size_t count, char *buf, size_t len, size_t *used) {
    size_t offset = 0;
    int n;

    if (readings == NULL || buf == NULL || len == 0 || used == NULL) {
        return -1;
    }
    if (count == 0 || count > JSON_MAX_READINGS) {
        return -1;
    }
    n = snprintf(buf + offset, len - offset, "[");
    if (n < 0 || (size_t)n >= len - offset) {
        return -1;
    }
    offset += (size_t)n;
    for (size_t i = 0; i < count; i++) {
        char one[256];
        if (json_reading(&readings[i], one, sizeof(one)) != 0) {
            return -1;
        }
        n = snprintf(buf + offset, len - offset, "%s%s", i == 0 ? "" : ",", one);
        if (n < 0 || (size_t)n >= len - offset) {
            return -1;
        }
        offset += (size_t)n;
    }
    n = snprintf(buf + offset, len - offset, "]");
    if (n < 0 || (size_t)n >= len - offset) {
        return -1;
    }
    offset += (size_t)n;
    *used = offset;
    return 0;
}
