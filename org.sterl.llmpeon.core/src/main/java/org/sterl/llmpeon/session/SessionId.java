package org.sterl.llmpeon.session;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one chat session (one UI tab). */
public record SessionId(UUID value) {

    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionId random() {
        return new SessionId(UUID.randomUUID());
    }

    public static SessionId parse(String value) {
        return new SessionId(UUID.fromString(value));
    }

    /** Stable id for headless/tests — one implicit session like before multi-session support. */
    public static SessionId defaultSession() {
        return new SessionId(new UUID(0L, 0L));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
