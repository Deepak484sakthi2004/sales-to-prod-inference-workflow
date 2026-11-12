package com.s2p.contracts;

/** Result of an idempotent write. replayed=true means the idempotency key was seen before and nothing new was created. */
public record Command<T>(T value, boolean replayed) {
}
