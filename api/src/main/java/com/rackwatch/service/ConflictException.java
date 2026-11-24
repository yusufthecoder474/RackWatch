package com.rackwatch.service;

/** The request conflicts with the current state of a record, e.g.
 * acknowledging an already-cleared alert. Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
