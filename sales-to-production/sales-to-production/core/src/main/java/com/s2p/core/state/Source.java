package com.s2p.core.state;

/** The systems of record. Each one is the source of truth for its own entities; the core holds only copies. */
public enum Source {
    MAILBOX, CRM, ERP, PLANNING, STATUS_FEED
}
