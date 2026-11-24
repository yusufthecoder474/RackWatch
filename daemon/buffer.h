#ifndef RACKWATCH_BUFFER_H
#define RACKWATCH_BUFFER_H

#include <signal.h>

#include "http.h"

/* At most this many batches are sent per buffer_flush() call. A huge
 * buffer therefore drains over several loop iterations instead of
 * stalling one iteration (and starving sampling) or delaying shutdown:
 * the remainder simply stays in the file for the next call. */
#define BUFFER_MAX_BATCHES_PER_FLUSH 5

/* Append one JSON line (a single reading object, no trailing newline)
 * to the append-only buffer file. If the file would grow past
 * max_bytes, the oldest lines are dropped first (and logged). The
 * stored line keeps the reading's original sampling timestamp: a
 * retry or a flush never re-stamps it. Returns 0 on success, -1 on
 * error. */
int buffer_append(const char *path, const char *line, long max_bytes);

/* Append one JSON line to the quarantine file. Capped at 5 MB: when
 * the file would grow past it, the oldest quarantined lines are
 * dropped first (and logged). Quarantine is for diagnosis, so the cap
 * is a guard against unbounded growth, not a retention policy — the
 * operator is still expected to inspect and clear it. Returns 0 on
 * success, -1 on error. */
int quarantine_append(const char *path, const char *line);

/* Number of lines in a file, or 0 when the file does not exist, or -1
 * on error. */
long buffer_line_count(const char *path);

/* Flush the buffer, oldest first: read the lines in file order, POST
 * them in batches of at most 100 readings and under 64 KiB, remove
 * the lines the server accepted, and move rejected batches (400/413/
 * 422) to the quarantine file so a bad batch can never block good
 * data. At most BUFFER_MAX_BATCHES_PER_FLUSH batches are sent per
 * call; the rest stays buffered for the next call. If `stop` is
 * non-NULL and *stop is set, the flush stops after the current batch
 * (a POST already in flight still runs to its 10 s timeout) and the
 * remaining lines stay buffered.
 * Returns HTTP_OK when the flush made progress without a failure
 * (the buffer may still hold more than 5 batches — call again to
 * drain it), otherwise the outcome of the attempt that stopped the
 * flush (HTTP_RETRYABLE or HTTP_AUTH or HTTP_MISCONFIGURED: the
 * remaining lines stay buffered). */
HttpOutcome buffer_flush(const char *path, const char *quarantine_path,
                         const char *endpoint_url, const char *api_key,
                         long max_bytes, const volatile sig_atomic_t *stop);

#endif /* RACKWATCH_BUFFER_H */
