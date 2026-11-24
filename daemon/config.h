#ifndef RACKWATCH_CONFIG_H
#define RACKWATCH_CONFIG_H

#include <stddef.h>

#include "reading.h"

#define MAX_SENSORS 64
#define DEFAULT_URL "http://localhost:8080"
#define DEFAULT_CONFIG_PATH "/etc/rackwatchd/sensors.conf"
#define DEFAULT_BUFFER_PATH "/var/lib/rackwatchd/buffer.jsonl"
#define DEFAULT_QUARANTINE_PATH "/var/lib/rackwatchd/quarantine.jsonl"
#define DEFAULT_MAX_BUFFER_BYTES (10L * 1024L * 1024L) /* 10 MB */
#define DEFAULT_INTERVAL_SEC 5

typedef struct {
    char sensor_id[32];
    SensorKind kind;
    SimMode mode;
    double base;
} SensorConfig;

typedef struct {
    SensorConfig sensors[MAX_SENSORS];
    size_t sensor_count;
    char url[256];
    char config_path[256];
    char buffer_path[256];
    char quarantine_path[256];
    long max_buffer_bytes;
    long interval_sec;
    long seed; /* -1 means "seed from time and pid" (not reproducible) */
    int once;
    int dry_run;
    int foreground;
} DaemonConfig;

/* Fill cfg with defaults, then apply argv. Returns 0 on success, 1
 * for --help (the caller prints usage), -1 on error (a one-line
 * message is written to errbuf). */
int parse_args(int argc, char **argv, DaemonConfig *cfg, char *errbuf, size_t errlen);

/* Load sensors.conf into cfg->sensors. Returns 0 on success, -1 on
 * error (message in errbuf). A cooling_failure mode on a humidity
 * sensor is downgraded to normal with a warning on stderr: a humidity
 * ramp has no physical meaning. */
int load_sensors_conf(DaemonConfig *cfg, char *errbuf, size_t errlen);

#endif /* RACKWATCH_CONFIG_H */
