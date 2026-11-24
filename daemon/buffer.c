#include "buffer.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <syslog.h>
#include <unistd.h>

#include "json.h"

#define TRIM_TARGET_NUM 4 /* after trimming, keep at most 4/5 of the cap */
#define TRIM_TARGET_DEN 5
#define READ_ALL_MAX (64L * 1024L * 1024L) /* never read a >64 MB buffer file whole */
#define QUARANTINE_MAX_BYTES (5L * 1024L * 1024L) /* 5 MB quarantine cap */

/* Append one line (plus newline) to a file, fsyncing so a crash does
 * not lose buffered readings. Returns 0 or -1. */
static int append_line_fsync(const char *path, const char *line) {
    FILE *fp = fopen(path, "a");
    if (fp == NULL) {
        syslog(LOG_ERR, "cannot open '%s' for append: %s", path, strerror(errno));
        return -1;
    }
    if (fputs(line, fp) == EOF || fputc('\n', fp) == EOF) {
        syslog(LOG_ERR, "cannot write to '%s': %s", path, strerror(errno));
        (void)fclose(fp);
        return -1;
    }
    if (fflush(fp) != 0 || fsync(fileno(fp)) != 0) {
        syslog(LOG_ERR, "cannot flush '%s': %s", path, strerror(errno));
        (void)fclose(fp);
        return -1;
    }
    if (fclose(fp) != 0) {
        syslog(LOG_ERR, "cannot close '%s': %s", path, strerror(errno));
        return -1;
    }
    return 0;
}

/* Count the '\n' characters in a memory block. */
static long count_lines(const char *data, size_t len) {
    long count = 0;
    for (size_t i = 0; i < len; i++) {
        if (data[i] == '\n') {
            count++;
        }
    }
    return count;
}

/* Drop the oldest lines so the file fits under the cap. Keeps the
 * newest lines totalling at most 4/5 of the cap. `kind` is "buffer"
 * or "quarantine" and appears in the log line. Returns 0 or -1. */
static int trim_file(const char *path, long max_bytes, const char *kind) {
    struct stat st;
    FILE *fp = NULL;
    char *data = NULL;
    char tmp_path[512];
    FILE *out = NULL;
    long target;
    size_t keep_from = 0;
    long dropped = 0;

    if (stat(path, &st) != 0) {
        return 0; /* no file yet: nothing to trim */
    }
    if (st.st_size > READ_ALL_MAX) {
        /* Safety valve: a file this big is corrupt or was written by
         * something else. Truncate rather than reading it whole. */
        syslog(LOG_WARNING, "%s '%s' is %lld bytes (cap %ld); truncating it",
               kind, path, (long long)st.st_size, max_bytes);
        fp = fopen(path, "w");
        if (fp == NULL) {
            syslog(LOG_ERR, "cannot truncate '%s': %s", path, strerror(errno));
            return -1;
        }
        (void)fclose(fp);
        return 0;
    }
    fp = fopen(path, "r");
    if (fp == NULL) {
        syslog(LOG_ERR, "cannot open '%s' for trimming: %s", path, strerror(errno));
        return -1;
    }
    data = (char *)malloc((size_t)st.st_size + 1);
    if (data == NULL) {
        (void)fclose(fp);
        return -1;
    }
    if (fread(data, 1, (size_t)st.st_size, fp) != (size_t)st.st_size) {
        syslog(LOG_ERR, "cannot read '%s' for trimming: %s", path, strerror(errno));
        free(data);
        (void)fclose(fp);
        return -1;
    }
    (void)fclose(fp);
    data[st.st_size] = '\0';

    target = max_bytes * TRIM_TARGET_NUM / TRIM_TARGET_DEN;
    if (st.st_size <= target) {
        /* Already at or under the keep-target. (The caller only calls
         * trim when the next append would pass the cap, so this is
         * normally unreachable — but a tiny file must never be
         * rewritten for nothing.) */
        free(data);
        return 0;
    }
    /* keep_from = first byte of the oldest line we keep. Walk forward
     * dropping whole lines while the remainder is over target. */
    {
        size_t total = (size_t)st.st_size;
        size_t pos = 0;
        while (total - pos > (size_t)target) {
            char *nl = memchr(data + pos, '\n', total - pos);
            if (nl == NULL) {
                pos = total; /* one huge line: drop everything */
                break;
            }
            pos = (size_t)(nl - data) + 1;
        }
        keep_from = pos;
    }
    dropped = count_lines(data, keep_from);

    if (snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", path) < 0) {
        free(data);
        return -1;
    }
    out = fopen(tmp_path, "w");
    if (out == NULL) {
        syslog(LOG_ERR, "cannot open '%s' for trimming: %s", tmp_path, strerror(errno));
        free(data);
        return -1;
    }
    if (keep_from < (size_t)st.st_size) {
        if (fwrite(data + keep_from, 1, (size_t)st.st_size - keep_from, out)
            != (size_t)st.st_size - keep_from) {
            syslog(LOG_ERR, "cannot write '%s': %s", tmp_path, strerror(errno));
            (void)fclose(out);
            (void)unlink(tmp_path);
            free(data);
            return -1;
        }
    }
    if (fflush(out) != 0 || fsync(fileno(out)) != 0 || fclose(out) != 0) {
        syslog(LOG_ERR, "cannot flush '%s': %s", tmp_path, strerror(errno));
        (void)unlink(tmp_path);
        free(data);
        return -1;
    }
    if (rename(tmp_path, path) != 0) {
        syslog(LOG_ERR, "cannot replace '%s': %s", path, strerror(errno));
        (void)unlink(tmp_path);
        free(data);
        return -1;
    }
    syslog(LOG_WARNING, "%s '%s' hit its %ld-byte cap; dropped %ld oldest readings",
           kind, path, max_bytes, dropped);
    free(data);
    return 0;
}

int buffer_append(const char *path, const char *line, long max_bytes) {
    struct stat st;
    size_t line_len = strlen(line);
    if (stat(path, &st) == 0 && (long)((size_t)st.st_size + line_len + 1) > max_bytes) {
        if (trim_file(path, max_bytes, "buffer") != 0) {
            return -1;
        }
    }
    return append_line_fsync(path, line);
}

int quarantine_append(const char *path, const char *line) {
    struct stat st;
    size_t line_len = strlen(line);
    if (stat(path, &st) == 0 &&
        (long)((size_t)st.st_size + line_len + 1) > QUARANTINE_MAX_BYTES) {
        if (trim_file(path, QUARANTINE_MAX_BYTES, "quarantine") != 0) {
            return -1;
        }
    }
    return append_line_fsync(path, line);
}

long buffer_line_count(const char *path) {
    FILE *fp = fopen(path, "r");
    char buf[4096];
    long count = 0;
    size_t n;
    if (fp == NULL) {
        if (errno == ENOENT) {
            return 0;
        }
        return -1;
    }
    while ((n = fread(buf, 1, sizeof(buf), fp)) > 0) {
        for (size_t i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                count++;
            }
        }
    }
    if (ferror(fp)) {
        (void)fclose(fp);
        return -1;
    }
    if (fclose(fp) != 0) {
        return -1;
    }
    return count;
}

static void free_lines(char **lines, size_t count);

/* Read every line of the file into a malloc'd array (each entry is a
 * NUL-terminated line without its newline). Returns 0 on success — a
 * missing file is success with zero lines — and -1 on a real error
 * (unreadable file, out of memory). The caller must free the array
 * with free_lines(). */
static int read_all_lines(const char *path, char ***lines_out, size_t *count_out) {
    FILE *fp = fopen(path, "r");
    char **lines = NULL;
    size_t count = 0;
    size_t cap = 0;
    char *line = NULL;
    size_t line_cap = 0;
    ssize_t len;
    int result = -1;

    *lines_out = NULL;
    *count_out = 0;
    if (fp == NULL) {
        if (errno == ENOENT) {
            return 0; /* no file yet: not an error, just empty */
        }
        syslog(LOG_ERR, "cannot open '%s' for flush: %s", path, strerror(errno));
        return -1;
    }
    while ((len = getline(&line, &line_cap, fp)) != -1) {
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }
        if (count == cap) {
            size_t new_cap = cap == 0 ? 128 : cap * 2;
            char **grown = (char **)realloc(lines, new_cap * sizeof(*lines));
            if (grown == NULL) {
                goto done;
            }
            lines = grown;
            cap = new_cap;
        }
        lines[count] = line;
        line = NULL;
        line_cap = 0;
        count++;
    }
    free(line);
    line = NULL;
    if (ferror(fp)) {
        syslog(LOG_ERR, "cannot read '%s' for flush: %s", path, strerror(errno));
        goto done;
    }
    result = 0;
done:
    /* `line` is NULL in the normal path (freed above, or stored in the
     * array). On the realloc-failure path it still owns the current
     * line, so free it here rather than leaking it. */
    free(line);
    if (fclose(fp) != 0 && result == 0) {
        syslog(LOG_ERR, "cannot close '%s': %s", path, strerror(errno));
        result = -1;
    }
    if (result != 0) {
        free_lines(lines, count);
        return -1;
    }
    *lines_out = lines;
    *count_out = count;
    return 0;
}

static void free_lines(char **lines, size_t count) {
    if (lines == NULL) {
        return;
    }
    for (size_t i = 0; i < count; i++) {
        free(lines[i]);
    }
    free(lines);
}

/* Rewrite the buffer file with lines[first..count). Unlinks the file
 * when nothing remains. Returns 0 or -1. */
static int rewrite_buffer(const char *path, char **lines, size_t first, size_t count) {
    char tmp_path[512];
    FILE *out;
    if (first >= count) {
        if (unlink(path) != 0 && errno != ENOENT) {
            syslog(LOG_ERR, "cannot remove emptied buffer '%s': %s", path, strerror(errno));
            return -1;
        }
        return 0;
    }
    if (snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", path) < 0) {
        return -1;
    }
    out = fopen(tmp_path, "w");
    if (out == NULL) {
        syslog(LOG_ERR, "cannot open '%s': %s", tmp_path, strerror(errno));
        return -1;
    }
    for (size_t i = first; i < count; i++) {
        if (fputs(lines[i], out) == EOF || fputc('\n', out) == EOF) {
            syslog(LOG_ERR, "cannot write '%s': %s", tmp_path, strerror(errno));
            (void)fclose(out);
            (void)unlink(tmp_path);
            return -1;
        }
    }
    if (fflush(out) != 0 || fsync(fileno(out)) != 0 || fclose(out) != 0) {
        syslog(LOG_ERR, "cannot flush '%s': %s", tmp_path, strerror(errno));
        (void)unlink(tmp_path);
        return -1;
    }
    if (rename(tmp_path, path) != 0) {
        syslog(LOG_ERR, "cannot replace '%s': %s", path, strerror(errno));
        (void)unlink(tmp_path);
        return -1;
    }
    return 0;
}

HttpOutcome buffer_flush(const char *path, const char *quarantine_path,
                         const char *endpoint_url, const char *api_key,
                         long max_bytes, const volatile sig_atomic_t *stop) {
    size_t total = 0;
    char **lines = NULL;
    size_t sent = 0; /* lines[0..sent) are done (sent or quarantined) */
    size_t batches = 0; /* batches sent this call (capped below) */
    HttpOutcome outcome = HTTP_OK;
    char resp[1024];

    (void)max_bytes; /* the cap is enforced on append, not on flush */
    if (read_all_lines(path, &lines, &total) != 0) {
        /* A genuinely unreadable buffer file must not be treated as
         * "nothing to flush": the data is stuck, and the caller
         * should back off and retry rather than move on silently. */
        return HTTP_RETRYABLE;
    }
    if (total == 0) {
        free_lines(lines, total);
        return HTTP_OK;
    }

    while (sent < total && batches < BUFFER_MAX_BATCHES_PER_FLUSH) {
        /* Stop promptly when the daemon is shutting down: the rest
         * stays buffered for the next start. (A POST already in
         * flight is not interrupted — it runs to its 10 s timeout.) */
        if (stop != NULL && *stop) {
            break;
        }
        /* Build one batch from raw lines: "[" line "," line ... "]".
         * At most 100 lines and under 64 KiB, mirroring json_batch. */
        size_t batch_count = 0;
        size_t batch_len = 1; /* "[" */
        size_t batch_start = sent;
        char *body = NULL;
        size_t body_len = 0;

        while (sent + batch_count < total && batch_count < JSON_MAX_READINGS) {
            size_t line_len = strlen(lines[sent + batch_count]);
            size_t need = batch_len + (batch_count > 0 ? 1 : 0) + line_len + 1; /* +"]" */
            if (need >= JSON_MAX_BODY) {
                break;
            }
            batch_len = need - 1; /* drop the "]" again; it is added at the end */
            batch_count++;
        }
        if (batch_count == 0) {
            /* One line alone does not fit in 64 KiB: it can never be
             * delivered. Quarantine it and move on. This counts toward
             * the per-call batch limit, so a buffer full of oversized
             * lines cannot stall one call either. */
            syslog(LOG_WARNING, "buffered line is larger than 64 KiB; quarantining it");
            if (quarantine_append(quarantine_path, lines[sent]) != 0) {
                outcome = HTTP_RETRYABLE;
                break;
            }
            sent++;
            batches++;
            continue;
        }
        body_len = batch_len + 1; /* closing "]" */
        body = (char *)malloc(body_len + 1);
        if (body == NULL) {
            outcome = HTTP_RETRYABLE;
            break;
        }
        {
            size_t off = 0;
            body[off++] = '[';
            for (size_t i = 0; i < batch_count; i++) {
                size_t line_len = strlen(lines[batch_start + i]);
                if (i > 0) {
                    body[off++] = ',';
                }
                memcpy(body + off, lines[batch_start + i], line_len);
                off += line_len;
            }
            body[off++] = ']';
            body[off] = '\0';
        }
        resp[0] = '\0';
        {
            long status = 0;
            outcome = http_post_json(endpoint_url, api_key, body, body_len,
                                     resp, sizeof(resp), &status);
            batches++;
            if (outcome == HTTP_MISCONFIGURED) {
                /* Log loudly, once per attempt: the daemon is pointed
                 * at the wrong URL (or at something that is not the
                 * RackWatch API). The data stays buffered — it is not
                 * the data's fault — and the caller retries slowly. */
                syslog(LOG_ERR,
                       "server returned HTTP %ld for %s; the daemon may be pointed at "
                       "the wrong URL (check --url) — buffered readings kept, retrying slowly",
                       status, endpoint_url);
            }
        }
        free(body);
        body = NULL;
        if (outcome == HTTP_OK) {
            sent += batch_count;
            continue;
        }
        if (outcome == HTTP_BAD_BATCH) {
            syslog(LOG_WARNING,
                   "server rejected a buffered batch (%zu readings); quarantining it%s%s",
                   batch_count, resp[0] != '\0' ? ": " : "", resp);
            for (size_t i = 0; i < batch_count; i++) {
                if (quarantine_append(quarantine_path, lines[batch_start + i]) != 0) {
                    outcome = HTTP_RETRYABLE;
                    break;
                }
            }
            if (outcome == HTTP_RETRYABLE) {
                break;
            }
            sent += batch_count;
            outcome = HTTP_OK; /* a quarantined batch does not block the next one */
            continue;
        }
        break; /* RETRYABLE, AUTH or MISCONFIGURED: stop, keep the rest buffered */
    }

    if (outcome == HTTP_OK && sent == total) {
        if (rewrite_buffer(path, lines, sent, total) != 0) {
            outcome = HTTP_RETRYABLE;
        }
    } else if (sent > 0) {
        /* Partial progress: keep the unsent remainder. */
        if (rewrite_buffer(path, lines, sent, total) != 0) {
            outcome = HTTP_RETRYABLE;
        }
    }
    free_lines(lines, total);
    return outcome;
}
