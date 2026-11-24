package com.rackwatch.model;

/** OPEN: needs attention. ACKED: someone has seen it, still unresolved.
 * CLEARED: the condition ended. ACKED is still unresolved, which is why
 * the partial unique index covers OPEN and ACKED together. */
public enum AlertState {
    OPEN,
    ACKED,
    CLEARED
}
