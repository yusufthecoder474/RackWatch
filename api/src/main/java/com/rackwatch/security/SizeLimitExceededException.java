package com.rackwatch.security;

/** Thrown when a request body grows past the configured limit while it
 * is being read. Mapped to HTTP 413 by GlobalExceptionHandler. */
public class SizeLimitExceededException extends RuntimeException {

    private final long limitBytes;
    private final long bytesRead;

    public SizeLimitExceededException(long limitBytes, long bytesRead) {
        super("Request body exceeds the limit of " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
        this.bytesRead = bytesRead;
    }

    public long getLimitBytes() {
        return limitBytes;
    }

    public long getBytesRead() {
        return bytesRead;
    }
}
