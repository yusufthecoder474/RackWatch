#ifndef RACKWATCH_JSON_H
#define RACKWATCH_JSON_H

#include <stddef.h>

#include "reading.h"

#define JSON_MAX_BODY 65536 /* 64 KiB: the API rejects larger bodies with 413 */
#define JSON_MAX_READINGS 100 /* the API rejects batches larger than this */

/* Serialize one reading as a single-line JSON object, exactly the field
 * names the API expects (snake_case). Returns 0 on success, -1 if the
 * object does not fit in buf. */
int json_reading(const Reading *r, char *buf, size_t len);

/* Serialize readings[0..count) as a JSON array batch. Returns 0 on
 * success, -1 if count > JSON_MAX_READINGS or the array does not fit
 * in buf. On success, *used is the number of bytes written. */
int json_batch(const Reading *readings, size_t count, char *buf, size_t len, size_t *used);

#endif /* RACKWATCH_JSON_H */
