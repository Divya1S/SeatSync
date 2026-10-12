package com.seatsync.concierge.tools;

import org.springframework.stereotype.Component;

/**
 * Counts concierge tool invocations during the current chat request.
 *
 * <p>Backed by a ThreadLocal: the default (synchronous) ChatClient tool
 * execution runs tool methods on the request thread, so a per-thread counter
 * is a reliable per-request counter. {@code reset()} before the LLM call,
 * {@code wasInvoked()} / {@code invocations()} after it, {@code clear()} in a
 * finally block. The count is what lets {@link ConciergeTools} enforce a
 * per-turn tool budget, so a model that keeps re-calling a tool cannot loop
 * indefinitely or run up the bill.
 */
@Component
public class ToolInvocationTracker {

    private final ThreadLocal<Integer> invocations = ThreadLocal.withInitial(() -> 0);

    public void reset() {
        invocations.set(0);
    }

    /** Records one invocation and returns the count so far in this request (1-based). */
    public int markInvoked() {
        int count = invocations.get() + 1;
        invocations.set(count);
        return count;
    }

    public boolean wasInvoked() {
        return invocations.get() > 0;
    }

    public int invocations() {
        return invocations.get();
    }

    public void clear() {
        invocations.remove();
    }
}
