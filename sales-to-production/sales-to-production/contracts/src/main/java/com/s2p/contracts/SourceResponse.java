package com.s2p.contracts;

import java.time.OffsetDateTime;

/**
 * Every read from a system of record carries the source's own version counter and the instant its data is valid for.
 * The intelligence layer never sees data without knowing how old it is.
 */
public record SourceResponse<T>(String source, long version, OffsetDateTime asOf, T data) {
}
