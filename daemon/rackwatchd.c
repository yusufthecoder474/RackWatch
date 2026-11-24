/* rackwatchd — RackWatch environment monitor daemon.
 *
 * Samples the configured sensors every --interval seconds, stamps each
 * reading at SAMPLING time (ISO-8601 UTC), batches the readings as JSON
 * and POSTs them to the RackWatch API with an API key. When the server
 * is unreachable the readings are appended to a local buffer file and
 * flushed in order when it returns; a batch the server rejects is
 * quarantined so it can never block good data.
 *
 * Runs in the foreground (systemd Type=simple); there is no double-fork
 * daemonisation. Logs go to syslog, and to stderr as well with
 * --foreground. The API key comes from RACKWATCH_API_KEY only and is
 * never logged. */

#include <curl/curl.h>
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <syslog.h>
#include <time.h>
#include <unistd.h>

#include "buffer.h"
#include "config.h"
#include "http.h"
#include "json.h"
#include "reading.h"
#include "simulate.h"

#define MAX_BACKOFF_SECS 60
#define AUTH_BACKOFF_SECS 60

static volatile sig_atomic_t stop_requested = 0;
static int g_foreground = 0;

static void on_signal(int sig) {
    (void)sig;
    stop_requested = 1;
}

static void log_msg(int prio, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsyslog(prio, fmt, ap);
    va_end(ap);
    if (g_foreground) {
        va_list ap2;
        va_start(ap2, fmt);
        (void)vfprintf(stderr, fmt, ap2);
        (void)fputc('\n', stderr);
        va_end(ap2);
    }
}

static void print_usage(FILE *out) {
    (void)fputs(
        "Usage: rackwatchd [OPTIONS]\n"
        "\n"
        "Options:\n"
        "  --config FILE            sensors.conf path (default /etc/rackwatchd/sensors.conf)\n"
        "  --url URL                API base URL (default http://localhost:8080)\n"
        "  --interval SECONDS       sampling interval, 1..3600 (default 5)\n"
        "  --seed N                 deterministic RNG seed (default: random)\n"
        "  --buffer FILE            buffer file (default /var/lib/rackwatchd/buffer.jsonl)\n"
        "  --quarantine FILE        quarantine file (default /var/lib/rackwatchd/quarantine.jsonl)\n"
        "  --max-buffer-bytes N     buffer cap, 1024..67108864 (default 10485760)\n"
        "  --once                   sample once and exit\n"
        "  --dry-run                print each batch as JSON, do not POST (no key needed)\n"
        "  --foreground             log to stderr as well as syslog\n"
        "  --help                   print this help\n"
        "\n"
        "The API key comes from the RACKWATCH_API_KEY environment variable only.\n",
        out);
}

/* mkdir -p for the parent directory of path. Returns 0 or -1. */
static int ensure_parent_dir(const char *path) {
    char dir[512];
    const char *slash = strrchr(path, '/');
    size_t len;
    if (slash == NULL || slash == path) {
        return 0; /* no parent directory to create */
    }
    len = (size_t)(slash - path);
    if (len >= sizeof(dir)) {
        return -1;
    }
    memcpy(dir, path, len);
    dir[len] = '\0';
    for (char *p = dir + 1; *p != '\0'; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(dir, 0755) != 0 && errno != EEXIST) {
                return -1;
            }
            *p = '/';
        }
    }
    if (mkdir(dir, 0755) != 0 && errno != EEXIST) {
        return -1;
    }
    return 0;
}

static long next_backoff(long current) {
    long next = current == 0 ? 1 : current * 2;
    return next > MAX_BACKOFF_SECS ? MAX_BACKOFF_SECS : next;
}

/* Sleep up to `seconds`, returning early when a stop was requested.
 * sleep() is interrupted by SIGTERM/SIGINT because the handlers are
 * installed without SA_RESTART. This is only the fallback when the
 * monotonic clock is unavailable; the normal path is absolute
 * scheduling below. */
static void sleep_or_stop(long seconds) {
    for (long i = 0; i < seconds && !stop_requested; i++) {
        (void)sleep(1);
    }
}

static void timespec_add_seconds(struct timespec *ts, long seconds) {
    ts->tv_sec += seconds;
}

static int timespec_cmp(const struct timespec *a, const struct timespec *b) {
    if (a->tv_sec != b->tv_sec) {
        return a->tv_sec < b->tv_sec ? -1 : 1;
    }
    if (a->tv_nsec != b->tv_nsec) {
        return a->tv_nsec < b->tv_nsec ? -1 : 1;
    }
    return 0;
}

/* Sleep until the absolute monotonic deadline, returning early on stop.
 * clock_nanosleep() with TIMER_ABSTIME returns EINTR when a signal
 * arrives; the loop re-checks the stop flag instead of resuming the
 * full sleep, so shutdown stays prompt. */
static void sleep_until(const struct timespec *deadline) {
    while (!stop_requested) {
        int rc = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, deadline, NULL);
        if (rc == 0) {
            return; /* deadline reached */
        }
        if (rc != EINTR) {
            return; /* a real error: do not spin */
        }
    }
}

/* Advance the absolute sampling schedule by one interval and sleep
 * until it. Sampling is scheduled on CLOCK_MONOTONIC, not on "sleep
 * N seconds after the last round finished": a slow round (a flush
 * that took seconds) cannot drift the grid, and a wall-clock jump
 * (NTP stepping the time) cannot bunch or stall rounds. If the
 * deadline is already past — a round overran its slot — the schedule
 * catches up to the next future slot instead of firing a burst of
 * back-to-back rounds. */
static void sleep_between_rounds(const DaemonConfig *cfg, struct timespec *next_wake,
                                 int have_mono) {
    struct timespec now_mono;
    if (!have_mono) {
        sleep_or_stop(cfg->interval_sec);
        return;
    }
    timespec_add_seconds(next_wake, cfg->interval_sec);
    if (clock_gettime(CLOCK_MONOTONIC, &now_mono) != 0) {
        sleep_or_stop(cfg->interval_sec);
        return;
    }
    while (timespec_cmp(next_wake, &now_mono) <= 0) {
        timespec_add_seconds(next_wake, cfg->interval_sec);
    }
    sleep_until(next_wake);
}

/* Store one round of readings in the buffer file, one JSON line each.
 * The stored lines keep their original sampling timestamps. */
static void buffer_current_batch(const DaemonConfig *cfg, const Reading *batch, size_t count) {
    for (size_t i = 0; i < count; i++) {
        char line[256];
        if (json_reading(&batch[i], line, sizeof(line)) != 0) {
            log_msg(LOG_ERR, "reading for sensor '%s' did not serialize (bug); dropping it",
                    batch[i].sensor_id);
            continue;
        }
        if (buffer_append(cfg->buffer_path, line, cfg->max_buffer_bytes) != 0) {
            log_msg(LOG_ERR, "cannot buffer reading for sensor '%s'; data lost",
                    batch[i].sensor_id);
        }
    }
}

static void quarantine_current_batch(const DaemonConfig *cfg, const Reading *batch,
                                     size_t count) {
    for (size_t i = 0; i < count; i++) {
        char line[256];
        if (json_reading(&batch[i], line, sizeof(line)) != 0) {
            continue;
        }
        if (quarantine_append(cfg->quarantine_path, line) != 0) {
            log_msg(LOG_ERR, "cannot quarantine reading for sensor '%s'; data lost",
                    batch[i].sensor_id);
        }
    }
}

int main(int argc, char **argv) {
    DaemonConfig cfg;
    char errbuf[512];
    const char *api_key = "";
    char endpoint[512];
    SensorSim sims[MAX_SENSORS];
    unsigned int base_seed;
    long backoff_sec = 0;
    time_t next_attempt = 0;
    struct timespec next_wake;
    int have_mono = 0;
    int rc = 0;
    int parsed;

    parsed = parse_args(argc, argv, &cfg, errbuf, sizeof(errbuf));
    if (parsed == 1) {
        print_usage(stdout);
        return 0;
    }
    if (parsed != 0) {
        (void)fprintf(stderr, "rackwatchd: %s\n", errbuf);
        return 2;
    }
    openlog("rackwatchd", LOG_PID | LOG_NDELAY, LOG_DAEMON);
    g_foreground = cfg.foreground;

    if (signal(SIGPIPE, SIG_IGN) == SIG_ERR) {
        log_msg(LOG_ERR, "cannot ignore SIGPIPE: %s", strerror(errno));
        rc = 1;
        goto out;
    }
    {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_signal;
        sa.sa_flags = 0; /* no SA_RESTART: sleep() must wake on signal */
        if (sigemptyset(&sa.sa_mask) != 0 || sigaction(SIGTERM, &sa, NULL) != 0 ||
            sigaction(SIGINT, &sa, NULL) != 0) {
            log_msg(LOG_ERR, "cannot install signal handlers: %s", strerror(errno));
            rc = 1;
            goto out;
        }
    }
    if (load_sensors_conf(&cfg, errbuf, sizeof(errbuf)) != 0) {
        log_msg(LOG_ERR, "%s", errbuf);
        rc = 2;
        goto out;
    }
    if (!cfg.dry_run) {
        api_key = getenv("RACKWATCH_API_KEY");
        if (api_key == NULL || api_key[0] == '\0') {
            log_msg(LOG_ERR,
                    "RACKWATCH_API_KEY is not set; refusing to run without an API key");
            rc = 2;
            goto out;
        }
    }
    {
        int n = snprintf(endpoint, sizeof(endpoint), "%s/api/v1/readings", cfg.url);
        if (n < 0 || (size_t)n >= sizeof(endpoint)) {
            log_msg(LOG_ERR, "--url is too long");
            rc = 2;
            goto out;
        }
    }
    if (ensure_parent_dir(cfg.buffer_path) != 0 ||
        ensure_parent_dir(cfg.quarantine_path) != 0) {
        log_msg(LOG_ERR, "cannot create buffer/quarantine directory: %s", strerror(errno));
        rc = 2;
        goto out;
    }
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
        log_msg(LOG_ERR, "curl_global_init failed");
        rc = 1;
        goto out;
    }
    if (cfg.seed >= 0) {
        base_seed = (unsigned int)cfg.seed;
    } else {
        base_seed = (unsigned int)(time(NULL) ^ ((long)getpid() << 16));
    }
    for (size_t i = 0; i < cfg.sensor_count; i++) {
        sim_init(&sims[i], &cfg.sensors[i], base_seed + (unsigned int)i);
    }
    /* Absolute schedule anchor: the first round runs immediately, the
     * next at anchor + interval, then + 2*interval, and so on. */
    have_mono = clock_gettime(CLOCK_MONOTONIC, &next_wake) == 0;
    if (!have_mono) {
        log_msg(LOG_WARNING, "CLOCK_MONOTONIC is unavailable; sampling will use "
                "relative sleeps and may drift");
    }
    log_msg(LOG_INFO, "started: %zu sensors, interval %ld s, url %s, buffer %s",
            cfg.sensor_count, cfg.interval_sec, cfg.url, cfg.buffer_path);

    while (!stop_requested) {
        time_t now = time(NULL);
        Reading batch[MAX_SENSORS];
        size_t count = 0;
        char body[JSON_MAX_BODY];
        size_t body_len = 0;

        /* One timestamp per round: every reading in the round is
         * sampled at the same moment, and that is its stamp. */
        for (size_t i = 0; i < cfg.sensor_count; i++) {
            batch[count++] = sim_sample(&sims[i], now);
        }

        if (cfg.dry_run) {
            if (json_batch(batch, count, body, sizeof(body), &body_len) != 0) {
                log_msg(LOG_ERR, "batch did not fit in 64 KiB (bug)");
            } else {
                (void)fputs(body, stdout);
                (void)fputc('\n', stdout);
                (void)fflush(stdout);
            }
            if (cfg.once) {
                break;
            }
            sleep_between_rounds(&cfg, &next_wake, have_mono);
            continue;
        }

        /* Flush buffered readings first (oldest first), if a retry is
         * due. A failed flush sets the backoff, and the current batch
         * is buffered below instead of posted. */
        if (now >= next_attempt) {
            long buffered = buffer_line_count(cfg.buffer_path);
            if (buffered < 0) {
                log_msg(LOG_ERR, "cannot read buffer file; continuing with live readings");
            } else if (buffered > 0) {
                HttpOutcome flushed = buffer_flush(cfg.buffer_path, cfg.quarantine_path,
                                                   endpoint, api_key, cfg.max_buffer_bytes,
                                                   &stop_requested);
                if (flushed == HTTP_OK) {
                    backoff_sec = 0;
                } else if (flushed == HTTP_AUTH) {
                    log_msg(LOG_ERR,
                            "server rejected the API key (401/403); check RACKWATCH_API_KEY "
                            "- buffered readings kept, retrying slowly");
                    backoff_sec = AUTH_BACKOFF_SECS;
                    next_attempt = now + backoff_sec;
                } else if (flushed == HTTP_MISCONFIGURED) {
                    /* buffer_flush already logged loudly (it has the
                     * status); just slow down here. */
                    backoff_sec = AUTH_BACKOFF_SECS;
                    next_attempt = now + backoff_sec;
                } else {
                    backoff_sec = next_backoff(backoff_sec);
                    next_attempt = now + backoff_sec;
                    log_msg(LOG_WARNING, "buffer flush failed; retrying in %ld s", backoff_sec);
                }
            }
        }

        if (now >= next_attempt) {
            if (json_batch(batch, count, body, sizeof(body), &body_len) != 0) {
                log_msg(LOG_ERR, "batch did not fit in 64 KiB (bug)");
            } else {
                char resp[1024];
                long status = 0;
                HttpOutcome posted = http_post_json(endpoint, api_key, body, body_len, resp,
                                                    sizeof(resp), &status);
                if (posted == HTTP_OK) {
                    backoff_sec = 0;
                } else if (posted == HTTP_RETRYABLE) {
                    buffer_current_batch(&cfg, batch, count);
                    backoff_sec = next_backoff(backoff_sec);
                    next_attempt = now + backoff_sec;
                    log_msg(LOG_WARNING, "cannot reach the API; %zu readings buffered, "
                            "retrying in %ld s", count, backoff_sec);
                } else if (posted == HTTP_AUTH) {
                    buffer_current_batch(&cfg, batch, count);
                    log_msg(LOG_ERR,
                            "server rejected the API key (401/403); check RACKWATCH_API_KEY "
                            "- %zu readings buffered, retrying slowly", count);
                    backoff_sec = AUTH_BACKOFF_SECS;
                    next_attempt = now + backoff_sec;
                } else if (posted == HTTP_MISCONFIGURED) {
                    buffer_current_batch(&cfg, batch, count);
                    log_msg(LOG_ERR,
                            "server returned HTTP %ld for %s; the daemon may be pointed at "
                            "the wrong URL (check --url) — %zu readings buffered, retrying slowly",
                            status, endpoint, count);
                    backoff_sec = AUTH_BACKOFF_SECS;
                    next_attempt = now + backoff_sec;
                } else { /* HTTP_BAD_BATCH */
                    quarantine_current_batch(&cfg, batch, count);
                    log_msg(LOG_WARNING,
                            "server rejected this batch; %zu readings quarantined%s%s",
                            count, resp[0] != '\0' ? ": " : "", resp);
                }
            }
        } else {
            /* Still backing off: keep sampling on schedule, buffer it. */
            buffer_current_batch(&cfg, batch, count);
        }

        if (cfg.once) {
            break;
        }
        sleep_between_rounds(&cfg, &next_wake, have_mono);
    }

    /* Clean shutdown: one last flush attempt, bounded by the 5-batch
     * limit and the curl timeouts (10 s at most). The stop flag is
     * already set, so it is deliberately NOT passed here: passing it
     * would make the flush stop before sending anything. The exit code
     * stays 0 either way: the data is safe in the buffer file. */
    if (!cfg.dry_run) {
        log_msg(LOG_INFO, "shutting down; flushing buffer once");
        (void)buffer_flush(cfg.buffer_path, cfg.quarantine_path, endpoint, api_key,
                           cfg.max_buffer_bytes, NULL);
    }
    curl_global_cleanup();
    log_msg(LOG_INFO, "stopped");

out:
    closelog();
    return rc;
}
