package com.s2p.contracts;

public class SourceUnavailableException extends RuntimeException {
    public SourceUnavailableException(String source, String detail) {
        super(source + " unavailable: " + detail);
    }
}
