#include "http.h"

#include <curl/curl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CONNECT_TIMEOUT_SECS 3L
#define TOTAL_TIMEOUT_SECS 10L

typedef struct {
    char *buf;
    size_t len; /* bytes stored, excluding the NUL */
    size_t cap; /* usable capacity, excluding the NUL */
} RespCapture;

/* libcurl write callback: copy at most cap bytes, discard the rest.
 * Returning fewer bytes than nmemb*size would abort the transfer, so
 * always report everything as consumed. */
static size_t write_capture(void *contents, size_t size, size_t nmemb, void *userp) {
    RespCapture *capture = (RespCapture *)userp;
    size_t incoming = size * nmemb;
    size_t room;
    if (capture == NULL || capture->buf == NULL) {
        return incoming; /* no capture buffer: discard */
    }
    room = capture->cap - capture->len;
    if (incoming > room) {
        incoming = room;
    }
    if (incoming > 0) {
        memcpy(capture->buf + capture->len, contents, incoming);
        capture->len += incoming;
        capture->buf[capture->len] = '\0';
    }
    return size * nmemb;
}

HttpOutcome classify_http_status(long status) {
    if (status >= 200 && status < 300) {
        return HTTP_OK;
    }
    if (status == 401 || status == 403) {
        return HTTP_AUTH;
    }
    if (status == 408 || status == 429 || (status >= 500 && status < 600)) {
        return HTTP_RETRYABLE; /* 408 (request timeout) is transient, like 5xx */
    }
    if (status == 404 || status == 405 || (status >= 300 && status < 400)) {
        /* Not "this batch is bad" — "we are talking to the wrong
         * thing". A 404/405/3xx means the URL is wrong or the server
         * is not the RackWatch API at all. Quarantining would silently
         * throw good data away; keeping it and retrying slowly while
         * logging loudly is the honest response. */
        return HTTP_MISCONFIGURED;
    }
    /* 400, 413, 422 and every other 4xx: the server will not accept
     * this batch as it stands. Quarantine it so it can never block
     * good data behind it. */
    return HTTP_BAD_BATCH;
}

HttpOutcome http_post_json(const char *endpoint_url, const char *api_key,
                           const char *body, size_t body_len,
                           char *resp_buf, size_t resp_len, long *status_out) {
    CURL *curl = NULL;
    struct curl_slist *headers = NULL;
    char *key_header = NULL;
    RespCapture capture;
    CURLcode res;
    long status = 0;
    HttpOutcome outcome = HTTP_RETRYABLE;

    if (endpoint_url == NULL || api_key == NULL || body == NULL) {
        return HTTP_BAD_BATCH; /* caller bug: nothing to send */
    }
    if (body_len > 0x7fffffffUL) {
        return HTTP_BAD_BATCH; /* CURLOPT_POSTFIELDSIZE takes a long */
    }

    curl = curl_easy_init();
    if (curl == NULL) {
        return HTTP_RETRYABLE;
    }
    /* The key header is built on the heap so a long key cannot
     * overflow a fixed buffer. It is freed below, never logged. */
    {
        size_t need = strlen(api_key) + strlen("X-API-Key: ") + 1;
        key_header = (char *)malloc(need);
        if (key_header == NULL) {
            curl_easy_cleanup(curl);
            return HTTP_RETRYABLE;
        }
        if (snprintf(key_header, need, "X-API-Key: %s", api_key) < 0) {
            free(key_header);
            curl_easy_cleanup(curl);
            return HTTP_RETRYABLE;
        }
    }
    headers = curl_slist_append(NULL, "Content-Type: application/json");
    if (headers == NULL) {
        free(key_header);
        curl_easy_cleanup(curl);
        return HTTP_RETRYABLE;
    }
    {
        struct curl_slist *next = curl_slist_append(headers, key_header);
        if (next == NULL) {
            curl_slist_free_all(headers);
            free(key_header);
            curl_easy_cleanup(curl);
            return HTTP_RETRYABLE;
        }
        headers = next;
    }
    free(key_header);
    key_header = NULL;

    capture.buf = resp_buf;
    capture.len = 0;
    capture.cap = (resp_buf != NULL && resp_len > 0) ? resp_len - 1 : 0;
    if (resp_buf != NULL && resp_len > 0) {
        resp_buf[0] = '\0';
    }

    (void)curl_easy_setopt(curl, CURLOPT_URL, endpoint_url);
    (void)curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    (void)curl_easy_setopt(curl, CURLOPT_POST, 1L);
    (void)curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body);
    (void)curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)body_len);
    (void)curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, CONNECT_TIMEOUT_SECS);
    (void)curl_easy_setopt(curl, CURLOPT_TIMEOUT, TOTAL_TIMEOUT_SECS);
    (void)curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_capture);
    (void)curl_easy_setopt(curl, CURLOPT_WRITEDATA, &capture);
    /* NOSIGNAL: libcurl must never use signals for its timeouts (the
     * daemon relies on signals for shutdown). Restrict protocols to
     * HTTP/HTTPS so a request can never go to an ftp:// or file://
     * URL, and a redirect (if following is ever enabled) can only go
     * to HTTP/HTTPS. The _STR variants are used because the long
     * CURLOPT_PROTOCOLS / CURLOPT_REDIR_PROTOCOLS are deprecated in
     * libcurl 7.85+; that pins the minimum libcurl to 7.85.0
     * (Debian bookworm ships 7.88, Ubuntu 24.04 ships 8.5). */
    (void)curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    (void)curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "http,https");
    (void)curl_easy_setopt(curl, CURLOPT_REDIR_PROTOCOLS_STR, "http,https");

    res = curl_easy_perform(curl);
    if (res != CURLE_OK) {
        /* Connection error, DNS failure, timeout: retryable. The
         * curl error string goes to the caller's log, never the key. */
        outcome = HTTP_RETRYABLE;
    } else if (curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status) != CURLE_OK) {
        outcome = HTTP_RETRYABLE;
    } else {
        outcome = classify_http_status(status);
    }
    if (status_out != NULL) {
        *status_out = status;
    }

    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    return outcome;
}
