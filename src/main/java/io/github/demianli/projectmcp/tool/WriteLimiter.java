package io.github.demianli.projectmcp.tool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.LongSupplier;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import org.springframework.stereotype.Component;

/**
 * Rate limits the Tools that write, at the numbers GitHub publishes for content-generating
 * requests: at most 80 in any 60 seconds and 500 in any hour.
 *
 * <p>Two sliding windows over the admission times of recent writes. A call over either limit
 * is refused with {@link Remedy#RETRY} and the number of seconds until a slot frees, before
 * any {@code gh} runs. Every admitted call counts, whatever happens to it afterwards: a write
 * that times out may still have landed.
 *
 * <p>The count lives in this process only. GitHub's limit belongs to the account, so writes
 * made with the same login from anywhere else are invisible here; this guarantees that this
 * Server alone stays inside the limit, not that the account does.
 *
 * <p>Reads are not limited. Their cost to GitHub depends on the query, so there is no
 * published per-call number to apply.
 */
@Component
public class WriteLimiter {

    static final int PER_MINUTE = 80;
    static final int PER_HOUR = 500;

    private static final long MINUTE_NANOS = 60_000_000_000L;
    private static final long HOUR_NANOS = 60 * MINUTE_NANOS;

    private final LongSupplier clock;
    private final Deque<Long> admitted = new ArrayDeque<>();

    public WriteLimiter() {
        this(System::nanoTime);
    }

    /** @param clock monotonic nanoseconds */
    WriteLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Admits one write, or throws.
     *
     * @throws ToolFailure {@link Remedy#RETRY} with {@code retryAfterSeconds} when a window is full
     */
    synchronized void acquire() {
        long now = clock.getAsLong();
        while (!admitted.isEmpty() && now - admitted.peekFirst() >= HOUR_NANOS) {
            admitted.removeFirst();
        }

        if (admitted.size() >= PER_HOUR) {
            throw full(PER_HOUR, "hour", admitted.peekFirst() + HOUR_NANOS - now);
        }
        if (admitted.size() >= PER_MINUTE) {
            long oldestInMinute = nthNewest(PER_MINUTE);
            if (now - oldestInMinute < MINUTE_NANOS) {
                throw full(PER_MINUTE, "minute", oldestInMinute + MINUTE_NANOS - now);
            }
        }
        admitted.addLast(now);
    }

    /** The admission time {@code n} places back from the newest; the deque holds at least n. */
    private long nthNewest(int n) {
        Iterator<Long> newestFirst = admitted.descendingIterator();
        long t = 0;
        for (int i = 0; i < n; i++) {
            t = newestFirst.next();
        }
        return t;
    }

    private static ToolFailure full(int limit, String window, long waitNanos) {
        int seconds = (int) Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
        return new ToolFailure(Remedy.RETRY,
                "This Server has already made " + limit + " writes in the last " + window
                        + ", GitHub's published limit for content-generating requests. Wait "
                        + seconds + " seconds before retrying.",
                "", seconds);
    }
}
