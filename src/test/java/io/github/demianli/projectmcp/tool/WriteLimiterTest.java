package io.github.demianli.projectmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.demianli.projectmcp.gh.Remedy;
import io.github.demianli.projectmcp.gh.ToolFailure;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Tests write rate limiting: 80 per minute, 500 per hour. */
class WriteLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final WriteLimiter limiter = new WriteLimiter(now::get);

    private void admit(int n) {
        for (int i = 0; i < n; i++) {
            limiter.acquire();
        }
    }

    private ToolFailure refusal() {
        try {
            limiter.acquire();
        } catch (ToolFailure e) {
            return e;
        }
        throw new AssertionError("expected the write to be refused");
    }

    @Test
    void eightyWritesInAMinuteAreAdmittedAndTheEightyFirstIsNot() {
        admit(WriteLimiter.PER_MINUTE);

        ToolFailure refused = refusal();
        assertThat(refused.remedy()).isEqualTo(Remedy.RETRY);
        assertThat(refused.retryAfterSeconds()).isEqualTo(60);
        assertThat(refused.stderr()).isEmpty();
        assertThat(refused.getMessage()).contains("80", "minute", "60 seconds");
    }

    @Test
    void theWaitCountsDownToWhenTheOldestWriteLeavesTheWindow() {
        admit(1);
        now.addAndGet(20 * SECOND);
        admit(WriteLimiter.PER_MINUTE - 1);

        now.addAndGet(15 * SECOND);
        assertThat(refusal().retryAfterSeconds()).isEqualTo(25);

        now.addAndGet(25 * SECOND);
        limiter.acquire();
    }

    @Test
    void aRefusedWriteDoesNotCount() {
        admit(WriteLimiter.PER_MINUTE);
        for (int i = 0; i < 10; i++) {
            refusal();
        }

        now.addAndGet(60 * SECOND);
        admit(WriteLimiter.PER_MINUTE);
    }

    @Test
    void fiveHundredWritesInAnHourAreTheCeilingEvenWhenEveryMinuteIsUnderEighty() {
        // 50 a minute stays under the minute window and reaches 500 after ten minutes.
        for (int minute = 0; minute < 10; minute++) {
            admit(50);
            now.addAndGet(60 * SECOND);
        }

        ToolFailure refused = refusal();
        assertThat(refused.getMessage()).contains("500", "hour");
        assertThat(refused.retryAfterSeconds()).isEqualTo(50 * 60);

        now.addAndGet(50 * 60 * SECOND);
        admit(50);
        assertThatThrownBy(limiter::acquire).isInstanceOf(ToolFailure.class);
    }
}
