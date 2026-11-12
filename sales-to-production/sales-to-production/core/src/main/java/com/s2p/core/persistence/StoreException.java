package com.s2p.core.persistence;

/** A store could not be reached or a stored record could not be read. */
public class StoreException extends RuntimeException {
    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreException(String message) {
        super(message);
    }
}
