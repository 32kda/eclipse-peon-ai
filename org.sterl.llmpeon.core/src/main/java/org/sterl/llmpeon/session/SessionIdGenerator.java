package org.sterl.llmpeon.session;

/** Test seam for deterministic session IDs. */
@FunctionalInterface
public interface SessionIdGenerator {

    SessionId next();

    static SessionIdGenerator random() {
        return SessionId::random;
    }
}
