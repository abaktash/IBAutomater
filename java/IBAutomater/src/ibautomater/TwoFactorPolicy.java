/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package ibautomater;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The Second Factor Authentication attempt state machine, kept free of Swing so it can be unit tested.
 * - a window that closes without our own timeout Cancel is an approval, whatever its elapsed time
 * - after {@link #FAST_ATTEMPTS} timeouts the login is retried every slowRetrySeconds while the retry
 * window lasts; only then is "2FA maximum attempts reached" written, which the C# side and the
 * brokerage treat as final
 */
final class TwoFactorPolicy {
    static final int FAST_ATTEMPTS = 3;
    static final int TIMEOUT_SECONDS = 150;
    static final int FAST_RETRY_DELAY_SECONDS = 10;
    static final int DEFAULT_SLOW_RETRY_SECONDS = 300;
    static final int DEFAULT_RETRY_HOURS = 12;

    enum Outcome { APPROVED, RETRY, EXHAUSTED, IGNORED }

    private final long slowRetrySeconds;
    private final long retryWindowSeconds;
    private final LongSupplier clockMillis;
    private final Consumer<String> log;

    private int attempts = 0;
    private int slowRetries = 0;
    private boolean windowOpen = false;
    private boolean cancelledByUs = false;
    private long openedAtMillis = 0;
    private boolean slowPhase = false;
    private long slowPhaseStartMillis = 0;
    private long retryDelaySeconds = 0;

    /**
     * Creates a new instance of the {@link TwoFactorPolicy} class.
     *
     * @param slowRetrySeconds The delay before each retry after the fast attempts
     * @param retryHours How long the slow retries last, from the third attempt's timeout; 0 stops after the fast attempts
     * @param clockMillis A monotonic clock in milliseconds
     * @param log The log line writer
     */
    TwoFactorPolicy(long slowRetrySeconds, long retryHours, LongSupplier clockMillis, Consumer<String> log) {
        this.slowRetrySeconds = slowRetrySeconds;
        this.retryWindowSeconds = retryHours * 3600;
        this.clockMillis = clockMillis;
        this.log = log;
    }

    /**
     * Records a newly opened 2FA confirmation window.
     *
     * @return Returns the attempt number, to be passed to {@link #claimTimeoutCancel(int)}
     */
    synchronized int onWindowOpened() {
        this.attempts++;
        this.windowOpen = true;
        this.cancelledByUs = false;
        this.openedAtMillis = this.clockMillis.getAsLong();
        this.log.accept("twoFactorConfirmationAttempts: " + this.attempts + "/" + FAST_ATTEMPTS);
        return this.attempts;
    }

    /**
     * Called by the timeout timer before it clicks Cancel; marks the coming close as our own.
     *
     * @param attempt The attempt the timer was started for
     *
     * @return Returns true if that attempt's window is still open and Cancel should be clicked
     */
    synchronized boolean claimTimeoutCancel(int attempt) {
        if (!this.windowOpen || attempt != this.attempts) {
            return false;
        }
        this.cancelledByUs = true;
        this.log.accept("Closing 2FA window after timeout" + this.detail());
        return true;
    }

    /**
     * Called by the timeout timer when the Cancel button was not found; the window stays open.
     */
    synchronized void onTimeoutCancelNotFound() {
        this.cancelledByUs = false;
        this.log.accept("2FA Cancel button not found" + this.detail());
    }

    /**
     * Records the close of the 2FA confirmation window and decides what follows.
     *
     * @return Returns the outcome; for {@link Outcome#RETRY} see {@link #getRetryDelaySeconds()}
     */
    synchronized Outcome onWindowClosed() {
        if (!this.windowOpen) {
            this.log.accept("2FA window closed with no attempt open");
            return Outcome.IGNORED;
        }

        long now = this.clockMillis.getAsLong();
        long elapsed = (now - this.openedAtMillis) / 1000;
        this.windowOpen = false;

        if (!this.cancelledByUs) {
            this.log.accept("2FA confirmation success after " + elapsed + " s (attempt " + this.attempts + ")");
            this.attempts = 0;
            this.slowRetries = 0;
            this.slowPhase = false;
            return Outcome.APPROVED;
        }

        this.log.accept("2FA confirmation timeout after " + elapsed + " s (attempt " + this.attempts + ")");

        if (this.attempts < FAST_ATTEMPTS) {
            this.retryDelaySeconds = (long) FAST_RETRY_DELAY_SECONDS * this.attempts;
            this.log.accept("New login attempt with 2FA in " + this.retryDelaySeconds + " s (attempt " + this.attempts + ")");
            return Outcome.RETRY;
        }

        if (!this.slowPhase) {
            this.slowPhase = true;
            this.slowPhaseStartMillis = now;
        }
        long slowElapsed = (now - this.slowPhaseStartMillis) / 1000;

        if (slowElapsed + this.slowRetrySeconds <= this.retryWindowSeconds) {
            this.slowRetries++;
            this.retryDelaySeconds = this.slowRetrySeconds;
            this.log.accept("2FA retry " + this.slowRetries + " in " + this.slowRetrySeconds + " s (attempt " + this.attempts
                    + ", " + slowElapsed + " of " + this.retryWindowSeconds + " s)");
            return Outcome.RETRY;
        }

        this.log.accept("2FA retries exhausted (attempt " + this.attempts + ", " + this.slowRetries + " slow retries in "
                + slowElapsed + " s)");
        this.log.accept("2FA maximum attempts reached");
        return Outcome.EXHAUSTED;
    }

    /**
     * Gets the delay before the retry decided by the last {@link #onWindowClosed()}.
     *
     * @return Returns the delay in seconds
     */
    synchronized long getRetryDelaySeconds() {
        return this.retryDelaySeconds;
    }

    /**
     * Gets whether the fast attempts are spent, so the Gateway's own Re-login must not add a push.
     *
     * @return Returns true once {@link #FAST_ATTEMPTS} attempts were made without an approval
     */
    synchronized boolean isPastFastAttempts() {
        return this.attempts >= FAST_ATTEMPTS;
    }

    private String detail() {
        return " (attempt " + this.attempts + ", " + (this.clockMillis.getAsLong() - this.openedAtMillis) / 1000 + " s)";
    }
}
