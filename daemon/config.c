#include "config.h"

#include <errno.h>
#include <getopt.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void set_defaults(DaemonConfig *cfg) {
    memset(cfg, 0, sizeof(*cfg));
    cfg->sensor_count = 0;
    if (snprintf(cfg->url, sizeof(cfg->url), "%s", DEFAULT_URL) < 0) {
        cfg->url[0] = '\0';
    }
    if (snprintf(cfg->config_path, sizeof(cfg->config_path), "%s", DEFAULT_CONFIG_PATH) < 0) {
        cfg->config_path[0] = '\0';
    }
    if (snprintf(cfg->buffer_path, sizeof(cfg->buffer_path), "%s", DEFAULT_BUFFER_PATH) < 0) {
        cfg->buffer_path[0] = '\0';
    }
    if (snprintf(cfg->quarantine_path, sizeof(cfg->quarantine_path), "%s",
                 DEFAULT_QUARANTINE_PATH) < 0) {
        cfg->quarantine_path[0] = '\0';
    }
    cfg->max_buffer_bytes = DEFAULT_MAX_BUFFER_BYTES;
    cfg->interval_sec = DEFAULT_INTERVAL_SEC;
    cfg->seed = -1;
    cfg->once = 0;
    cfg->dry_run = 0;
    cfg->foreground = 0;
}

/* Strictly parse a long in [lo, hi]. Returns 0 or -1. */
static int parse_long_range(const char *s, long lo, long hi, long *out) {
    char *end = NULL;
    long value;
    if (s == NULL || s[0] == '\0' || out == NULL) {
        return -1;
    }
    errno = 0;
    value = strtol(s, &end, 10);
    if (errno != 0 || end == s || *end != '\0' || value < lo || value > hi) {
        return -1;
    }
    *out = value;
    return 0;
}

static int set_string(char *dst, size_t dst_len, const char *src, const char *name,
                      char *errbuf, size_t errlen) {
    int n = snprintf(dst, dst_len, "%s", src);
    if (n < 0 || (size_t)n >= dst_len) {
        (void)snprintf(errbuf, errlen, "%s is too long (max %zu characters)", name, dst_len - 1);
        return -1;
    }
    return 0;
}

int parse_args(int argc, char **argv, DaemonConfig *cfg, char *errbuf, size_t errlen) {
    static const struct option long_opts[] = {
        {"config", required_argument, NULL, 'c'},
        {"url", required_argument, NULL, 'u'},
        {"interval", required_argument, NULL, 'i'},
        {"seed", required_argument, NULL, 's'},
        {"buffer", required_argument, NULL, 'b'},
        {"quarantine", required_argument, NULL, 'q'},
        {"max-buffer-bytes", required_argument, NULL, 'm'},
        {"once", no_argument, NULL, '1'},
        {"dry-run", no_argument, NULL, 'd'},
        {"foreground", no_argument, NULL, 'f'},
        {"help", no_argument, NULL, 'h'},
        {NULL, 0, NULL, 0},
    };
    int opt;
    long value;

    set_defaults(cfg);
    /* getopt_long permutes argv; reset for re-entrancy in tests. */
    optind = 1;
    while ((opt = getopt_long(argc, argv, "c:u:i:s:b:q:m:1dfh", long_opts, NULL)) != -1) {
        switch (opt) {
            case 'c':
                if (set_string(cfg->config_path, sizeof(cfg->config_path), optarg,
                               "--config", errbuf, errlen) != 0) {
                    return -1;
                }
                break;
            case 'u':
                if (set_string(cfg->url, sizeof(cfg->url), optarg, "--url", errbuf, errlen) != 0) {
                    return -1;
                }
                break;
            case 'i':
                if (parse_long_range(optarg, 1, 3600, &value) != 0) {
                    (void)snprintf(errbuf, errlen,
                                   "--interval must be 1..3600 seconds, got '%s'", optarg);
                    return -1;
                }
                cfg->interval_sec = value;
                break;
            case 's':
                if (parse_long_range(optarg, 0, LONG_MAX, &value) != 0) {
                    (void)snprintf(errbuf, errlen,
                                   "--seed must be a non-negative integer, got '%s'", optarg);
                    return -1;
                }
                cfg->seed = value;
                break;
            case 'b':
                if (set_string(cfg->buffer_path, sizeof(cfg->buffer_path), optarg,
                               "--buffer", errbuf, errlen) != 0) {
                    return -1;
                }
                break;
            case 'q':
                if (set_string(cfg->quarantine_path, sizeof(cfg->quarantine_path), optarg,
                               "--quarantine", errbuf, errlen) != 0) {
                    return -1;
                }
                break;
            case 'm':
                /* Capped at 64 MB: the buffer file is read whole on
                 * flush, so an unbounded cap is an unbounded memory
                 * allocation. 10 MB is the default; 64 MB is the most
                 * the daemon will accept. */
                if (parse_long_range(optarg, 1024, 64L * 1024L * 1024L, &value) != 0) {
                    (void)snprintf(errbuf, errlen,
                                   "--max-buffer-bytes must be 1024..67108864, got '%s'",
                                   optarg);
                    return -1;
                }
                cfg->max_buffer_bytes = value;
                break;
            case '1':
                cfg->once = 1;
                break;
            case 'd':
                cfg->dry_run = 1;
                break;
            case 'f':
                cfg->foreground = 1;
                break;
            case 'h':
                return 1; /* --help: the caller prints usage and exits 0 */
            default:
                (void)snprintf(errbuf, errlen,
                               "unknown option; see --help (usage is printed by rackwatchd)");
                return -1;
        }
    }
    if (optind < argc) {
        (void)snprintf(errbuf, errlen, "unexpected argument '%s'", argv[optind]);
        return -1;
    }
    return 0;
}

/* Sensor ids must match [A-Za-z0-9_-]{1,31}: the API stores them as
 * the sensor primary key, and the daemon embeds them in JSON and file
 * paths, so spaces, dots, slashes and other punctuation are rejected
 * here instead of failing later as a 400 or a broken path. */
static int valid_sensor_id(const char *s) {
    size_t len = strlen(s);
    if (len < 1 || len > 31) {
        return 0;
    }
    for (size_t i = 0; i < len; i++) {
        char c = s[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '-')) {
            return 0;
        }
    }
    return 1;
}

int load_sensors_conf(DaemonConfig *cfg, char *errbuf, size_t errlen) {
    FILE *fp = fopen(cfg->config_path, "r");
    char line[512];
    unsigned long lineno = 0;

    if (fp == NULL) {
        (void)snprintf(errbuf, errlen, "cannot open sensors config '%s': %s",
                       cfg->config_path, strerror(errno));
        return -1;
    }
    cfg->sensor_count = 0;
    while (fgets(line, sizeof(line), fp) != NULL) {
        /* Wide temp buffers on purpose: an overlong id must be caught
         * whole by valid_sensor_id() below, not silently split across
         * fields by a narrow %31s. */
        char id[64];
        char kind_s[32];
        char mode_s[64];
        double base = 0.0;
        SensorConfig *sensor;
        int fields;
        lineno++;
        /* Skip blank lines and comments. A line longer than the buffer
         * is a config error, not something to silently truncate. */
        if (line[0] == '\n' || line[0] == '#') {
            continue;
        }
        if (strchr(line, '\n') == NULL && !feof(fp)) {
            (void)snprintf(errbuf, errlen, "%s:%lu: line too long (max %zu characters)",
                           cfg->config_path, lineno, sizeof(line) - 1);
            (void)fclose(fp);
            return -1;
        }
        fields = sscanf(line, "%63s %31s %63s %lf", id, kind_s, mode_s, &base);
        if (fields == 0) {
            continue; /* whitespace-only line */
        }
        if (fields != 4) {
            (void)snprintf(errbuf, errlen,
                           "%s:%lu: expected 'sensor_id kind mode base', got %d fields",
                           cfg->config_path, lineno, fields);
            (void)fclose(fp);
            return -1;
        }
        if (!valid_sensor_id(id)) {
            (void)snprintf(errbuf, errlen,
                           "%s:%lu: sensor id '%s' must match [A-Za-z0-9_-]{1,31}",
                           cfg->config_path, lineno, id);
            (void)fclose(fp);
            return -1;
        }
        if (cfg->sensor_count >= MAX_SENSORS) {
            (void)snprintf(errbuf, errlen, "%s: too many sensors (max %d)",
                           cfg->config_path, MAX_SENSORS);
            (void)fclose(fp);
            return -1;
        }
        sensor = &cfg->sensors[cfg->sensor_count];
        if (parse_kind(kind_s, &sensor->kind) != 0) {
            (void)snprintf(errbuf, errlen, "%s:%lu: unknown kind '%s' (want temp|humidity)",
                           cfg->config_path, lineno, kind_s);
            (void)fclose(fp);
            return -1;
        }
        if (parse_mode(mode_s, &sensor->mode) != 0) {
            (void)snprintf(errbuf, errlen,
                           "%s:%lu: unknown mode '%s' (want normal|cooling_failure|stuck)",
                           cfg->config_path, lineno, mode_s);
            (void)fclose(fp);
            return -1;
        }
        if (!isfinite(base)) {
            (void)snprintf(errbuf, errlen, "%s:%lu: base value is not a finite number",
                           cfg->config_path, lineno);
            (void)fclose(fp);
            return -1;
        }
        if (snprintf(sensor->sensor_id, sizeof(sensor->sensor_id), "%s", id) < 0) {
            (void)snprintf(errbuf, errlen, "%s:%lu: sensor id did not fit", cfg->config_path,
                           lineno);
            (void)fclose(fp);
            return -1;
        }
        for (size_t i = 0; i < cfg->sensor_count; i++) {
            if (strcmp(cfg->sensors[i].sensor_id, sensor->sensor_id) == 0) {
                (void)snprintf(errbuf, errlen, "%s:%lu: duplicate sensor id '%s'",
                               cfg->config_path, lineno, sensor->sensor_id);
                (void)fclose(fp);
                return -1;
            }
        }
        sensor->base = base;
        if (sensor->mode == MODE_COOLING_FAILURE && sensor->kind == KIND_HUMIDITY) {
            (void)fprintf(stderr,
                          "rackwatchd: %s:%lu: cooling_failure on humidity sensor '%s' "
                          "has no meaning; treating it as normal\n",
                          cfg->config_path, lineno, sensor->sensor_id);
            sensor->mode = MODE_NORMAL;
        }
        cfg->sensor_count++;
    }
    if (ferror(fp)) {
        (void)snprintf(errbuf, errlen, "error reading '%s': %s", cfg->config_path,
                       strerror(errno));
        (void)fclose(fp);
        return -1;
    }
    if (fclose(fp) != 0) {
        (void)snprintf(errbuf, errlen, "error closing '%s': %s", cfg->config_path,
                       strerror(errno));
        return -1;
    }
    if (cfg->sensor_count == 0) {
        (void)snprintf(errbuf, errlen, "'%s' defines no sensors", cfg->config_path);
        return -1;
    }
    return 0;
}
