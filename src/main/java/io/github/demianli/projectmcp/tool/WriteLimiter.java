package io.github.demianli.projectmcp.tool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.LongSupplier;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import org.springframework.stereotype.Component;

/**
 * 以 GitHub 公布的上限限制寫入 Tools：每分鐘 80 次、每小時 500 次。用兩個滑動視窗追蹤
 * 近期寫入的放行時間。超過任一上限的寫入，在任何 gh 執行前就以 {@link Remedy#RETRY}
 * 加上重試等待時間拒絕。
 *
 * <p>上限以 Server 行程為單位，不是以帳號為單位。同一帳號在其他 client 的寫入不在此計算。
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

    /** @param clock 單調遞增的奈秒值 */
    WriteLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 放行一次寫入，或拋出例外。
     *
     * @throws ToolFailure 任一視窗已滿時，為附帶 {@code retryAfterSeconds} 的
     *     {@link Remedy#RETRY}
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

    /** 從最新一筆往回數第 {@code n} 筆的放行時間；deque 至少有 n 筆。 */
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
