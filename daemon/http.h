#ifndef RACKWATCH_HTTP_H
#define RACKWATCH_HTTP_H

#include <stddef.h>

/* What a POST attempt means for the caller. The classification is the
 * retry policy: only RETRYABLE outcomes are retried with backoff.
 *
 *   2xx            OK            the server accepted the batch
 *   401, 403       AUTH          the key is wrong; keep the data, retry slowly
 *   408, 429, 5xx  RETRYABLE     transient; keep the data, retry with backoff
 *   400, 413, 422  BAD_BATCH     this batch will never be accepted; quarantine it
 *   404, 405, 3xx  MISCONFIGURED the daemon is talking to the wrong URL (or to
 *                                something that is not the RackWatch API);
 *                                keep the data, retry slowly, log loudly
 *   any other 4xx  BAD_BATCH     the server rejected this batch; quarantine it
 */
typedef enum {
    HTTP_OK, /* 2xx: the server accepted the batch */
    HTTP_RETRYABLE, /* connection error, timeout, 408, 429, 5xx */
    HTTP_AUTH, /* 401/403: the key is wrong; keep the data, retry slowly */
    HTTP_BAD_BATCH, /* 400/413/422 (and any other 4xx): quarantine the batch */
    HTTP_MISCONFIGURED /* 404/405/3xx: wrong URL; keep the data, retry slowly */
} HttpOutcome;

/* POST a JSON body to the full endpoint URL (base + /api/v1/readings).
 * Timeouts: connect 3 s, total 10 s. The API key is sent only in the
 * X-API-Key header and is never written to any log. If resp_buf is
 * non-NULL, up to resp_len-1 bytes of the response body are captured
 * there (NUL-terminated) for diagnostics on error responses. If
 * status_out is non-NULL, the HTTP status code is written there (0
 * when no HTTP response was received at all).
 * Requires curl_global_init() to have been called once by the caller. */
HttpOutcome http_post_json(const char *endpoint_url, const char *api_key,
                           const char *body, size_t body_len,
                           char *resp_buf, size_t resp_len, long *status_out);

/* Pure classifier, unit-testable without a network. */
HttpOutcome classify_http_status(long status);

#endif /* RACKWATCH_HTTP_H */
