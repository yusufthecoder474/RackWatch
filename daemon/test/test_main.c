/* RackWatch daemon — C unit tests.
 *
 * Tests the pure parts: timestamp formatting, JSON building, the
 * simulator, config parsing, the buffer file, and HTTP status
 * classification. No network is used. Run with `make test`. */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "../buffer.h"
#include "../config.h"
#include "../http.h"
#include "../json.h"
#include "../reading.h"
#include "../simulate.h"

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond) do { \
    g_checks++; \
    if (!(cond)) { \
        g_failures++; \
        (void)fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
    } \
} while (0)

#define CHECK_STR_EQ(a, b) do { \
    g_checks++; \
    if (strcmp((a), (b)) != 0) { \
        g_failures++; \
        (void)fprintf(stderr, "FAIL %s:%d: \"%s\" != \"%s\"\n", __FILE__, __LINE__, \
                      (a), (b)); \
    } \
} while (0)

#define CHECK_NEAR(a, b, eps) do { \
    g_checks++; \
    if (fabs((a) - (b)) > (eps)) { \
        g_failures++; \
        (void)fprintf(stderr, "FAIL %s:%d: %f != %f (+/-%f)\n", __FILE__, __LINE__, \
                      (double)(a), (double)(b), (double)(eps)); \
    } \
} while (0)

static Reading temp_reading(const char *id, time_t ts, double value) {
    Reading r;
    memset(&r, 0, sizeof(r));
    (void)snprintf(r.sensor_id, sizeof(r.sensor_id), "%s", id);
    (void)format_ts_utc(ts, r.ts, sizeof(r.ts));
    r.temp_c = value;
    r.has_temp = 1;
    return r;
}

static void test_format_ts(void) {
    char buf[32];
    struct tm tmv;
    time_t t;

    CHECK(format_ts_utc(0, buf, sizeof(buf)) == 0);
    CHECK_STR_EQ(buf, "1970-01-01T00:00:00Z");
    CHECK(format_ts_utc(0, buf, 10) != 0); /* too small a buffer */

    memset(&tmv, 0, sizeof(tmv));
    tmv.tm_year = 2026 - 1900;
    tmv.tm_mon = 10; /* November */
    tmv.tm_mday = 23;
    tmv.tm_hour = 12;
    tmv.tm_min = 0;
    tmv.tm_sec = 0;
    t = timegm(&tmv);
    CHECK(t != (time_t)-1);
    CHECK(format_ts_utc(t, buf, sizeof(buf)) == 0);
    CHECK_STR_EQ(buf, "2026-11-23T12:00:00Z");
}

static void test_json_reading(void) {
    char buf[256];
    Reading temp = temp_reading("ROOM-A-TEMP-01", 0, 22.5);
    Reading rh;
    Reading both;
    Reading neither;

    memset(&rh, 0, sizeof(rh));
    (void)snprintf(rh.sensor_id, sizeof(rh.sensor_id), "%s", "ROOM-A-RH-01");
    (void)format_ts_utc(0, rh.ts, sizeof(rh.ts));
    rh.rh_pct = 50.0;
    rh.has_rh = 1;

    CHECK(json_reading(&temp, buf, sizeof(buf)) == 0);
    CHECK_STR_EQ(buf,
                 "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"1970-01-01T00:00:00Z\","
                 "\"temp_c\":22.5}");
    CHECK(json_reading(&rh, buf, sizeof(buf)) == 0);
    CHECK_STR_EQ(buf,
                 "{\"sensor_id\":\"ROOM-A-RH-01\",\"ts\":\"1970-01-01T00:00:00Z\","
                 "\"rh_pct\":50.0}");
    CHECK(json_reading(&temp, buf, 10) != 0); /* too small a buffer */

    both = temp;
    both.has_rh = 1;
    CHECK(json_reading(&both, buf, sizeof(buf)) != 0); /* both values: rejected */
    neither = temp;
    neither.has_temp = 0;
    CHECK(json_reading(&neither, buf, sizeof(buf)) != 0); /* no value: rejected */
}

static void test_json_batch(void) {
    char body[JSON_MAX_BODY];
    size_t used = 0;
    Reading readings[101];
    Reading two[2];

    two[0] = temp_reading("ROOM-A-TEMP-01", 0, 22.5);
    two[1] = temp_reading("ROOM-A-TEMP-01", 60, 22.6);
    CHECK(json_batch(two, 2, body, sizeof(body), &used) == 0);
    CHECK_STR_EQ(body,
                 "[{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"1970-01-01T00:00:00Z\","
                 "\"temp_c\":22.5},"
                 "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"1970-01-01T00:01:00Z\","
                 "\"temp_c\":22.6}]");
    CHECK(used == strlen(body));
    CHECK(json_batch(two, 2, body, 64, &used) != 0); /* too small a buffer */
    CHECK(json_batch(two, 0, body, sizeof(body), &used) != 0); /* empty batch */

    for (int i = 0; i < 101; i++) {
        readings[i] = temp_reading("ROOM-A-TEMP-01", i * 60, 22.0);
    }
    CHECK(json_batch(readings, 101, body, sizeof(body), &used) != 0); /* over 100 */
    CHECK(json_batch(readings, 100, body, sizeof(body), &used) == 0); /* exactly 100 */
}

static void test_simulate_normal(void) {
    SensorConfig cfg;
    SensorSim sim;
    int blips = 0;
    int prev_blip = 0;

    memset(&cfg, 0, sizeof(cfg));
    (void)snprintf(cfg.sensor_id, sizeof(cfg.sensor_id), "%s", "ROOM-A-TEMP-01");
    cfg.kind = KIND_TEMP;
    cfg.mode = MODE_NORMAL;
    cfg.base = 22.0;
    sim_init(&sim, &cfg, 42u);
    for (int i = 0; i < 1000; i++) {
        Reading r = sim_sample(&sim, 1000000 + i * 5);
        double v = r.temp_c;
        int is_blip = v > 27.0;
        CHECK(r.has_temp && !r.has_rh);
        CHECK(v >= 21.0 && v <= 29.0);
        if (is_blip) {
            blips++;
            CHECK_NEAR(v, 28.5, 0.01); /* temp blips are base + 6.5 */
            CHECK(!prev_blip); /* a blip is a single reading */
        } else {
            CHECK(v >= 21.7 && v <= 22.3); /* base +/- 0.3 noise */
        }
        prev_blip = is_blip;
    }
    CHECK(blips > 0 && blips < 20); /* ~1/300 per reading: expect ~3 in 1000 */
}

/* The API contract test. POST /api/v1/readings takes a bare JSON
 * array of ReadingRequest objects (api/src/main/java/com/rackwatch/
 * web/ReadingController.java: @RequestBody @Size(min=1, max=100)
 * List<@Valid ReadingRequest>), and ReadingRequest
 * (web/dto/ReadingRequest.java) is:
 *   sensorId  String, @NotBlank            -> "sensor_id"
 *   ts        Instant, @NotNull @PastOrPresent -> "ts" (ISO-8601 UTC)
 *   tempC     Double, -40.0..100.0, nullable -> "temp_c"
 *   rhPct     Double, 0.0..100.0, nullable   -> "rh_pct"
 * with exactly one of temp_c / rh_pct present (@AssertTrue), and
 * snake_case from the global SNAKE_CASE strategy in application.yml.
 * This test pins the daemon's output byte-for-byte to that contract:
 * if the daemon's JSON drifts (a renamed field, a wrapper object, an
 * extra field, a different number format) this test fails. If the DTO
 * changes, update the literal below to match — the literal IS the
 * contract, quoted from the DTO. */
static void test_api_contract(void) {
    char body[JSON_MAX_BODY];
    size_t used = 0;
    Reading batch[2];

    batch[0] = temp_reading("ROOM-A-TEMP-01", 1795435200, 22.5); /* 2026-11-23T12:00:00Z */
    memset(&batch[1], 0, sizeof(batch[1]));
    (void)snprintf(batch[1].sensor_id, sizeof(batch[1].sensor_id), "%s", "ROOM-A-RH-01");
    (void)format_ts_utc(1795435260, batch[1].ts, sizeof(batch[1].ts)); /* 12:01:00Z */
    batch[1].rh_pct = 50.0;
    batch[1].has_rh = 1;

    CHECK(json_batch(batch, 2, body, sizeof(body), &used) == 0);
    CHECK_STR_EQ(body,
                 "[{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"2026-11-23T12:00:00Z\","
                 "\"temp_c\":22.5},"
                 "{\"sensor_id\":\"ROOM-A-RH-01\",\"ts\":\"2026-11-23T12:01:00Z\","
                 "\"rh_pct\":50.0}]");
    /* A bare array, not a wrapper object: the controller takes a
     * List, so {"readings": [...]} would be a 400. */
    CHECK(body[0] == '[');
    /* Exactly one value per reading, as @AssertTrue requires. */
    CHECK(batch[0].has_temp && !batch[0].has_rh);
    CHECK(!batch[1].has_temp && batch[1].has_rh);
}

static void test_simulate_timestamp_is_sampling_time(void) {
    SensorConfig cfg;
    SensorSim sim;
    Reading r;

    memset(&cfg, 0, sizeof(cfg));
    (void)snprintf(cfg.sensor_id, sizeof(cfg.sensor_id), "%s", "ROOM-A-TEMP-01");
    cfg.kind = KIND_TEMP;
    cfg.mode = MODE_NORMAL;
    cfg.base = 22.0;
    sim_init(&sim, &cfg, 42u);
    r = sim_sample(&sim, 1795435200); /* 2026-11-23T12:00:00Z */
    CHECK_STR_EQ(r.ts, "2026-11-23T12:00:00Z");
}

static void test_simulate_cooling_failure(void) {
    SensorConfig cfg;
    SensorSim sim;
    double values[60];

    memset(&cfg, 0, sizeof(cfg));
    (void)snprintf(cfg.sensor_id, sizeof(cfg.sensor_id), "%s", "ROOM-B-TEMP-01");
    cfg.kind = KIND_TEMP;
    cfg.mode = MODE_COOLING_FAILURE;
    cfg.base = 22.0;
    sim_init(&sim, &cfg, 42u);
    for (int i = 0; i < 60; i++) {
        values[i] = sim_sample(&sim, 1000000 + i * 5).temp_c;
    }
    CHECK_NEAR(values[0], 22.5, 0.15); /* first ramp step */
    CHECK_NEAR(values[19], 32.0, 0.25); /* top of the ramp */
    CHECK(values[19] > values[0] + 8.0);
    for (int i = 30; i < 60; i++) {
        CHECK(values[i] >= 31.8 && values[i] <= 32.2); /* held, with noise */
    }
}

static void test_simulate_stuck(void) {
    SensorConfig cfg;
    SensorSim sim;
    double values[120];
    double lo = 1e9, hi = -1e9;

    memset(&cfg, 0, sizeof(cfg));
    (void)snprintf(cfg.sensor_id, sizeof(cfg.sensor_id), "%s", "ROOM-A-TEMP-01");
    cfg.kind = KIND_TEMP;
    cfg.mode = MODE_STUCK;
    cfg.base = 22.0;
    sim_init(&sim, &cfg, 42u);
    for (int i = 0; i < 120; i++) {
        values[i] = sim_sample(&sim, 1000000 + i * 5).temp_c;
    }
    for (int i = 0; i < 30; i++) {
        if (values[i] < lo) lo = values[i];
        if (values[i] > hi) hi = values[i];
    }
    CHECK(hi > lo); /* the first 30 readings vary */
    for (int i = 31; i < 120; i++) {
        CHECK(values[i] == values[30]); /* flatline, bit-identical */
    }
    CHECK_NEAR(values[30], 22.5, 0.01); /* in-range flatline value */
}

static void test_simulate_seed_reproducible(void) {
    SensorConfig cfg;
    SensorSim a, b;
    memset(&cfg, 0, sizeof(cfg));
    (void)snprintf(cfg.sensor_id, sizeof(cfg.sensor_id), "%s", "ROOM-A-TEMP-01");
    cfg.kind = KIND_TEMP;
    cfg.mode = MODE_NORMAL;
    cfg.base = 22.0;
    sim_init(&a, &cfg, 7u);
    sim_init(&b, &cfg, 7u);
    for (int i = 0; i < 50; i++) {
        Reading ra = sim_sample(&a, 1000000 + i * 5);
        Reading rb = sim_sample(&b, 1000000 + i * 5);
        CHECK(ra.temp_c == rb.temp_c); /* same seed: same stream */
    }
}

static void remove_dir(const char *dir) {
    char cmd[512];
    int rc;
    (void)snprintf(cmd, sizeof(cmd), "rm -rf %s", dir);
    rc = system(cmd);
    CHECK(rc == 0);
}

static void write_file(const char *path, const char *content) {
    FILE *fp = fopen(path, "w");
    CHECK(fp != NULL);
    if (fp == NULL) {
        return;
    }
    CHECK(fputs(content, fp) != EOF);
    CHECK(fclose(fp) == 0);
}

static void test_config_parse(void) {
    char dir_template[] = "/tmp/rwtestXXXXXX";
    char *dir = mkdtemp(dir_template);
    char path[512];
    char err[512];
    DaemonConfig cfg;
    char *argv_ok[] = {"rackwatchd", "--config", path, "--interval", "5", "--seed", "42"};

    CHECK(dir != NULL);
    if (dir == NULL) {
        return;
    }
    (void)snprintf(path, sizeof(path), "%s/sensors.conf", dir);

    write_file(path,
               "# comment\n"
               "\n"
               "ROOM-A-TEMP-01 temp normal 22.0\n"
               "ROOM-A-RH-01 humidity normal 50.0\n"
               "ROOM-B-TEMP-01 temp cooling_failure 22.0\n"
               "ROOM-B-RH-01 humidity stuck 50.0\n");
    CHECK(parse_args(7, argv_ok, &cfg, err, sizeof(err)) == 0);
    CHECK(cfg.interval_sec == 5);
    CHECK(cfg.seed == 42);
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) == 0);
    CHECK(cfg.sensor_count == 4);
    CHECK(cfg.sensors[0].kind == KIND_TEMP && cfg.sensors[0].mode == MODE_NORMAL);
    CHECK_NEAR(cfg.sensors[0].base, 22.0, 0.001);
    CHECK(cfg.sensors[2].mode == MODE_COOLING_FAILURE);
    CHECK(cfg.sensors[3].mode == MODE_STUCK);

    write_file(path, "ROOM-A-TEMP-01 temp bogus 22.0\n");
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) != 0); /* unknown mode */
    write_file(path,
               "ROOM-A-TEMP-01 temp normal 22.0\n"
               "ROOM-A-TEMP-01 temp normal 22.0\n");
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) != 0); /* duplicate id */
    write_file(path, "# only a comment\n");
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) != 0); /* no sensors */
    write_file(path, "ROOM-A-RH-01 humidity cooling_failure 50.0\n");
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) == 0);
    CHECK(cfg.sensors[0].mode == MODE_NORMAL); /* humidity ramp downgraded */

    /* Sensor ids must match [A-Za-z0-9_-]{1,31}. */
    write_file(path, "A_1-2 temp normal 22.0\n");
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) == 0);
    CHECK_STR_EQ(cfg.sensors[0].sensor_id, "A_1-2");
    {
        char id31[32];
        memset(id31, 'A', 31);
        id31[31] = '\0';
        char line[128];
        (void)snprintf(line, sizeof(line), "%s temp normal 22.0\n", id31);
        write_file(path, line);
        CHECK(load_sensors_conf(&cfg, err, sizeof(err)) == 0); /* 31 chars: ok */
    }
    write_file(path, "BAD.ID temp normal 22.0\n");
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) != 0); /* dot */
    write_file(path, "bad/id temp normal 22.0\n");
    CHECK(load_sensors_conf(&cfg, err, sizeof(err)) != 0); /* slash */
    {
        char id32[33];
        memset(id32, 'A', 32);
        id32[32] = '\0';
        char line[128];
        (void)snprintf(line, sizeof(line), "%s temp normal 22.0\n", id32);
        write_file(path, line);
        CHECK(load_sensors_conf(&cfg, err, sizeof(err)) != 0); /* 32 chars: too long */
    }

    {
        char *argv_bad[] = {"rackwatchd", "--interval", "0"};
        CHECK(parse_args(3, argv_bad, &cfg, err, sizeof(err)) != 0);
        char *argv_help[] = {"rackwatchd", "--help"};
        CHECK(parse_args(2, argv_help, &cfg, err, sizeof(err)) == 1);
        /* --max-buffer-bytes is capped at 64 MB. */
        char *argv_cap_ok[] = {"rackwatchd", "--max-buffer-bytes", "67108864"};
        CHECK(parse_args(3, argv_cap_ok, &cfg, err, sizeof(err)) == 0);
        CHECK(cfg.max_buffer_bytes == 67108864);
        char *argv_cap_bad[] = {"rackwatchd", "--max-buffer-bytes", "67108865"};
        CHECK(parse_args(3, argv_cap_bad, &cfg, err, sizeof(err)) != 0);
    }
    remove_dir(dir);
}

static void test_buffer(void) {
    char dir_template[] = "/tmp/rwtestXXXXXX";
    char *dir = mkdtemp(dir_template);
    char path[512];
    char qpath[512];
    char line[256];
    char read_back[256];
    FILE *fp;

    CHECK(dir != NULL);
    if (dir == NULL) {
        return;
    }
    (void)snprintf(path, sizeof(path), "%s/buffer.jsonl", dir);
    (void)snprintf(qpath, sizeof(qpath), "%s/quarantine.jsonl", dir);

    CHECK(buffer_line_count(path) == 0); /* missing file: zero lines */
    /* A missing file in a missing directory is still "no file" (ENOENT),
     * so the error path is exercised on a directory instead: reading a
     * directory fails, which must report -1. */
    CHECK(buffer_line_count(dir) == -1);
    for (int i = 0; i < 5; i++) {
        (void)snprintf(line, sizeof(line),
                       "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"2026-11-23T12:00:0%dZ\","
                       "\"temp_c\":22.5}", i);
        CHECK(buffer_append(path, line, 10 * 1024 * 1024) == 0);
    }
    CHECK(buffer_line_count(path) == 5);
    fp = fopen(path, "r");
    CHECK(fp != NULL);
    if (fp != NULL) {
        CHECK(fgets(read_back, sizeof(read_back), fp) != NULL);
        CHECK(strstr(read_back, "12:00:00Z") != NULL); /* oldest first */
        CHECK(fclose(fp) == 0);
    }
    CHECK(quarantine_append(qpath, line) == 0);
    CHECK(buffer_line_count(qpath) == 1);

    /* Trim: a 400-byte cap on ~72-byte lines keeps about 4 newest. */
    for (int i = 5; i < 12; i++) {
        (void)snprintf(line, sizeof(line),
                       "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"2026-11-23T12:00:%02dZ\","
                       "\"temp_c\":22.5}", i);
        CHECK(buffer_append(path, line, 400) == 0);
    }
    {
        struct stat st;
        CHECK(stat(path, &st) == 0);
        CHECK(st.st_size <= 400);
    }
    CHECK(buffer_line_count(path) == 5); /* 360 bytes: the 5 newest lines */
    fp = fopen(path, "r");
    CHECK(fp != NULL);
    if (fp != NULL) {
        char last[256] = "";
        int lines = 0;
        CHECK(fgets(read_back, sizeof(read_back), fp) != NULL);
        CHECK(strstr(read_back, "12:00:00Z") == NULL); /* oldest dropped */
        while (fgets(read_back, sizeof(read_back), fp) != NULL) {
            (void)snprintf(last, sizeof(last), "%s", read_back);
            lines++;
        }
        CHECK(lines == 4); /* 1 first line + 4 more = 5 total */
        CHECK(strstr(last, "12:00:11Z") != NULL); /* newest line kept */
        CHECK(fclose(fp) == 0);
    }

    /* Trim early return: a file already at or under the 4/5
     * keep-target is left alone. (Cap 400, target 320: 4 lines of
     * ~72 bytes = 288 bytes, then a 150-byte line pushes the append
     * past the cap, but 288 <= 320, so nothing is dropped.) */
    {
        char path2[512];
        char long_line[256];
        (void)snprintf(path2, sizeof(path2), "%s/buffer2.jsonl", dir);
        for (int i = 0; i < 4; i++) {
            (void)snprintf(line, sizeof(line),
                           "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"2026-11-23T12:00:%02dZ\","
                           "\"temp_c\":22.5}", i);
            CHECK(buffer_append(path2, line, 400) == 0);
        }
        memset(long_line, 'x', 150);
        long_line[150] = '\0';
        CHECK(buffer_append(path2, long_line, 400) == 0);
        CHECK(buffer_line_count(path2) == 5); /* nothing dropped */
    }
    remove_dir(dir);
}

static void test_classify_http_status(void) {
    CHECK(classify_http_status(200) == HTTP_OK);
    CHECK(classify_http_status(201) == HTTP_OK);
    CHECK(classify_http_status(204) == HTTP_OK);
    CHECK(classify_http_status(401) == HTTP_AUTH);
    CHECK(classify_http_status(403) == HTTP_AUTH);
    CHECK(classify_http_status(400) == HTTP_BAD_BATCH);
    CHECK(classify_http_status(413) == HTTP_BAD_BATCH);
    CHECK(classify_http_status(422) == HTTP_BAD_BATCH);
    CHECK(classify_http_status(409) == HTTP_BAD_BATCH); /* other 4xx: quarantine */
    CHECK(classify_http_status(408) == HTTP_RETRYABLE); /* request timeout: transient */
    CHECK(classify_http_status(429) == HTTP_RETRYABLE);
    CHECK(classify_http_status(500) == HTTP_RETRYABLE);
    CHECK(classify_http_status(502) == HTTP_RETRYABLE);
    CHECK(classify_http_status(503) == HTTP_RETRYABLE);
    /* 404/405/3xx: misconfigured, not a bad batch — the data is kept. */
    CHECK(classify_http_status(404) == HTTP_MISCONFIGURED);
    CHECK(classify_http_status(405) == HTTP_MISCONFIGURED);
    CHECK(classify_http_status(301) == HTTP_MISCONFIGURED);
    CHECK(classify_http_status(302) == HTTP_MISCONFIGURED);
    CHECK(classify_http_status(307) == HTTP_MISCONFIGURED);
}

/* The quarantine file is capped at 5 MB, dropping the oldest lines.
 * Pre-fill it past the cap with one big write (no fsync per line),
 * then one quarantine_append must trim it back under the cap. */
static void test_quarantine_cap(void) {
    char dir_template[] = "/tmp/rwtestXXXXXX";
    char *dir = mkdtemp(dir_template);
    char path[512];
    char line[256];
    char first[256];
    FILE *fp;
    struct stat st;

    CHECK(dir != NULL);
    if (dir == NULL) {
        return;
    }
    (void)snprintf(path, sizeof(path), "%s/quarantine.jsonl", dir);
    fp = fopen(path, "w");
    CHECK(fp != NULL);
    if (fp == NULL) {
        remove_dir(dir);
        return;
    }
    for (int i = 0; i < 72000; i++) {
        (void)snprintf(line, sizeof(line),
                       "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"2026-11-23T12:%02d:%02dZ\","
                       "\"temp_c\":22.5}", (i / 60) % 60, i % 60);
        CHECK(fputs(line, fp) != EOF);
        CHECK(fputc('\n', fp) != EOF);
    }
    CHECK(fclose(fp) == 0);
    CHECK(stat(path, &st) == 0);
    CHECK(st.st_size > 5L * 1024L * 1024L); /* pre-filled past the 5 MB cap */
    fp = fopen(path, "r");
    CHECK(fp != NULL);
    if (fp != NULL) {
        CHECK(fgets(first, sizeof(first), fp) != NULL);
        CHECK(fclose(fp) == 0);
    }
    (void)snprintf(line, sizeof(line),
                   "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"2026-11-23T13:00:00Z\","
                   "\"temp_c\":22.5}");
    CHECK(quarantine_append(path, line) == 0);
    CHECK(stat(path, &st) == 0);
    CHECK(st.st_size <= 5L * 1024L * 1024L);
    fp = fopen(path, "r");
    CHECK(fp != NULL);
    if (fp != NULL) {
        char now_first[256];
        CHECK(fgets(now_first, sizeof(now_first), fp) != NULL);
        CHECK(strcmp(now_first, first) != 0); /* oldest lines dropped */
        CHECK(fclose(fp) == 0);
    }
    remove_dir(dir);
}

int main(void) {
    test_format_ts();
    test_json_reading();
    test_json_batch();
    test_api_contract();
    test_simulate_normal();
    test_simulate_timestamp_is_sampling_time();
    test_simulate_cooling_failure();
    test_simulate_stuck();
    test_simulate_seed_reproducible();
    test_config_parse();
    test_buffer();
    test_classify_http_status();
    test_quarantine_cap();
    (void)printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
