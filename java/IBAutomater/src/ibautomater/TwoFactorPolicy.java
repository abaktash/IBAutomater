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
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The Second Factor Authentication attempt state machine, kept free of Swing so it can be unit tested.
 * - a window that closes without our own timeout Cancel is held for {@link #APPROVAL_HOLD_SECONDS}: a
 * "Re-login is required" window inside the hold makes it a timeout by IB's own expiry, otherwise it is
 * an approval, whatever its elapsed time
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
    static final int APPROVAL_HOLD_SECONDS = 5;
    static final String PROPERTY_RETRY_HOURS = "ibautomater.twofa.retryHours";
    static final String PROPERTY_SLOW_RETRY_SECONDS = "ibautomater.twofa.slowRetrySeconds";

    enum Outcome { APPROVED, HELD, RETRY, EXHAUSTED, IGNORED }

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
    private boolean holdPending = false;
    private long heldElapsedSeconds = 0;

    /**
     * Creates a new instance of the {@link TwoFactorPolicy} class.
     *
     * @param slowRetrySeconds The delay before each retry after the fast attempts
     * @param retryHours How long the slow retries last, from the third attempt's timeout; 0 stops after the fast attempts
     * @param clockMillis A monotonic clock in milliseconds
     * @param log The log line writer
     */
    TwoFactorPolicy(long slowRetrySeconds, double retryHours, LongSupplier clockMillis, Consumer<String> log) {
        this.slowRetrySeconds = slowRetrySeconds;
        this.retryWindowSeconds = Math.round(retryHours * 3600);
        this.clockMillis = clockMillis;
        this.log = log;
    }

    /**
     * Creates the policy from the {@link #PROPERTY_RETRY_HOURS} and {@link #PROPERTY_SLOW_RETRY_SECONDS} system
     * properties the C# side passes to the gateway's JVM; if either is absent or invalid both take their defaults.
     * Writes the policy line.
     *
     * @param properties The property reader, normally System::getProperty
     * @param clockMillis A monotonic clock in milliseconds
     * @param log The log line writer
     *
     * @return Returns the policy
     */
    static TwoFactorPolicy fromProperties(Function<String, String> properties, LongSupplier clockMillis, Consumer<String> log) {
        double retryHours = DEFAULT_RETRY_HOURS;
        long slowRetrySeconds = DEFAULT_SLOW_RETRY_SECONDS;
        String source;

        String hoursText = properties.apply(PROPERTY_RETRY_HOURS);
        String slowText = properties.apply(PROPERTY_SLOW_RETRY_SECONDS);
        Double hours = parseHours(hoursText);
        Long slow = parseSlowSeconds(slowText);

        if (hoursText == null) {
            source = "defaults: " + PROPERTY_RETRY_HOURS + " absent";
        }
        else if (hours == null) {
            source = "defaults: " + PROPERTY_RETRY_HOURS + "=" + hoursText + " invalid (0 to 24)";
        }
        else if (slowText == null) {
            source = "defaults: " + PROPERTY_SLOW_RETRY_SECONDS + " absent";
        }
        else if (slow == null) {
            source = "defaults: " + PROPERTY_SLOW_RETRY_SECONDS + "=" + slowText + " invalid (60 to 3600)";
        }
        else {
            retryHours = hours;
            slowRetrySeconds = slow;
            source = "from system properties";
        }

        String hoursShown = retryHours == Math.rint(retryHours) ? Long.toString((long) retryHours) : Double.toString(retryHours);
        log.accept("2FA policy: slow-retry-seconds=" + slowRetrySeconds + " retry-hours=" + hoursShown + " (" + source + ")");
        return new TwoFactorPolicy(slowRetrySeconds, retryHours, clockMillis, log);
    }

    private static Double parseHours(String text) {
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) && value >= 0 && value <= 24 ? value : null;
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Long parseSlowSeconds(String text) {
        if (text == null) {
            return null;
        }
        try {
            long value = Long.parseLong(text.trim());
            return value >= 60 && value <= 3600 ? value : null;
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Records a newly opened 2FA confirmation window.
     *
     * @return Returns the attempt number, to be passed to {@link #claimTimeoutCancel(int)}
     */
    synchronized int onWindowOpened() {
        if (this.holdPending) {
            this.settleHoldAsApproval();
        }
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
     * @return Returns the outcome; for {@link Outcome#HELD} call {@link #onApprovalHoldElapsed()} after
     * {@link #APPROVAL_HOLD_SECONDS}, and {@link #onReloginRequired()} if a "Re-login is required" window opens
     * first; for {@link Outcome#RETRY} see {@link #getRetryDelaySeconds()}
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
            this.holdPending = true;
            this.heldElapsedSeconds = elapsed;
            return Outcome.HELD;
        }

        this.log.accept("2FA confirmation timeout after " + elapsed + " s (attempt " + this.attempts + ")");
        return this.afterTimeout(now);
    }

    /**
     * Called {@link #APPROVAL_HOLD_SECONDS} after a close without our Cancel.
     *
     * @return Returns {@link Outcome#APPROVED} if the hold was still pending, otherwise {@link Outcome#IGNORED}
     */
    synchronized Outcome onApprovalHoldElapsed() {
        if (!this.holdPending) {
            return Outcome.IGNORED;
        }
        this.settleHoldAsApproval();
        return Outcome.APPROVED;
    }

    /**
     * Called when a "Re-login is required" window opens, before it is handled; inside a hold it is IB's own
     * expiry of the 2FA request, so the held close becomes a timeout.
     *
     * @return Returns the timeout's outcome ({@link Outcome#RETRY} or {@link Outcome#EXHAUSTED}) if a hold was
     * pending, otherwise {@link Outcome#IGNORED}
     */
    synchronized Outcome onReloginRequired() {
        if (!this.holdPending) {
            return Outcome.IGNORED;
        }
        this.holdPending = false;
        this.log.accept("2FA confirmation timeout after " + this.heldElapsedSeconds + " s (attempt " + this.attempts + ", IB expiry)");
        return this.afterTimeout(this.clockMillis.getAsLong());
    }

    private void settleHoldAsApproval() {
        this.holdPending = false;
        this.log.accept("2FA confirmation success after " + this.heldElapsedSeconds + " s (attempt " + this.attempts + ")");
        this.attempts = 0;
        this.slowRetries = 0;
        this.slowPhase = false;
    }

    private Outcome afterTimeout(long now) {
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
