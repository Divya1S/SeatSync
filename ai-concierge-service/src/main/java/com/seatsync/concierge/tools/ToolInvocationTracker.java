package com.seatsync.concierge.tools;

import org.springframework.stereotype.Component;

/**
 * Tracks whether any concierge tool ran during the current chat request.
 *
 * <p>Backed by a ThreadLocal: the default (synchronous) ChatClient tool
 * execution runs tool methods on the request thread, so a per-thread flag is
 * a reliable per-request flag. {@code reset()} before the LLM call,
 * {@code wasInvoked()} after it, {@code clear()} in a finally block.
 */
@Component
public class ToolInvocationTracker {

    private final ThreadLocal<Boolean> invoked = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public void reset() {
        invoked.set(Boolean.FALSE);
    }

    public void markInvoked() {
        invoked.set(Boolean.TRUE);
    }

    public boolean wasInvoked() {
        return invoked.get();
    }

    public void clear() {
        invoked.remove();
    }
}
