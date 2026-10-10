package com.padnote.android;

import java.util.concurrent.locks.ReentrantLock;

/** Process-local serialization gate for legacy members while a full-group adoption publishes.
 * All legacy stores use this same gate; it does not claim cross-process writer coordination. */
public final class LegacyGroupMutationLock {
    private static final ReentrantLock LOCK=new ReentrantLock(true);
    private LegacyGroupMutationLock() {}
    public static Lease acquire(){LOCK.lock();return new Lease();}
    public static final class Lease implements AutoCloseable {
        private boolean closed;
        private Lease() {}
        @Override public void close(){if(!closed){closed=true;LOCK.unlock();}}
    }
}
