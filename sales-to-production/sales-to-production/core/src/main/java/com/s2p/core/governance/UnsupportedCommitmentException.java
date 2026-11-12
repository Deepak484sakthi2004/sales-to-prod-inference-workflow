package com.s2p.core.governance;

/** Thrown whenever something would present a date or quantity that current capacity and stock do not support. */
public class UnsupportedCommitmentException extends RuntimeException {
    public UnsupportedCommitmentException(String message) {
        super(message);
    }
}
