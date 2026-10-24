package com.vmalloc.model;

/** Lifecycle of a streamed (live) simulation session. */
public enum SessionStatus {
    RUNNING,
    COMPLETED,
    STOPPED,
    FAILED
}
