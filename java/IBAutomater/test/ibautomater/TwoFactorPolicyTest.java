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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class TwoFactorPolicyTest {
    private long nowMillis;
    private List<String> lines;
    private TwoFactorPolicy policy;

    @Before
    public void setUp() {
        this.nowMillis = 0;
        this.lines = new ArrayList<>();
        this.policy = this.newPolicy(TwoFactorPolicy.DEFAULT_RETRY_HOURS);
    }

    @Test
    public void approvalBefore150SecondsIsASuccess() {
        this.policy.onWindowOpened();
        this.advance(40);

        assertEquals(TwoFactorPolicy.Outcome.APPROVED, this.policy.onWindowClosed());
        assertEquals("2FA confirmation success after 40 s (attempt 1)", this.last());
    }

    @Test
    public void approvalAfter150SecondsIsASuccessWhenOurCancelDidNotClick() {
        int attempt = this.policy.onWindowOpened();
        this.advance(TwoFactorPolicy.TIMEOUT_SECONDS);
        assertTrue(this.policy.claimTimeoutCancel(attempt));
        this.policy.onTimeoutCancelNotFound();
        this.advance(22);

        assertEquals(TwoFactorPolicy.Outcome.APPROVED, this.policy.onWindowClosed());
        assertEquals("2FA confirmation success after 172 s (attempt 1)", this.last());
        assertEquals(0, this.count("2FA confirmation timeout"));
    }

    @Test
    public void closeWithoutOurCancelIsASuccessWhateverTheElapsedTime() {
        this.policy.onWindowOpened();
        this.advance(600);

        assertEquals(TwoFactorPolicy.Outcome.APPROVED, this.policy.onWindowClosed());
        assertEquals("2FA confirmation success after 600 s (attempt 1)", this.last());
    }

    @Test
    public void ourCancelIsATimeoutAndTheFastRetriesFollow() {
        assertEquals(TwoFactorPolicy.Outcome.RETRY, this.timedOutAttempt());
        assertEquals(10, this.policy.getRetryDelaySeconds());
        assertTrue(this.lines.contains("twoFactorConfirmationAttempts: 1/3"));
        assertTrue(this.lines.contains("Closing 2FA window after timeout (attempt 1, 150 s)"));
        assertTrue(this.lines.contains("2FA confirmation timeout after 150 s (attempt 1)"));
        assertEquals("New login attempt with 2FA in 10 s (attempt 1)", this.last());

        this.advance(10);
        assertEquals(TwoFactorPolicy.Outcome.RETRY, this.timedOutAttempt());
        assertEquals(20, this.policy.getRetryDelaySeconds());
        assertEquals("New login attempt with 2FA in 20 s (attempt 2)", this.last());
        assertFalse(this.policy.isPastFastAttempts());
    }

    @Test
    public void threeTimeoutsThenSlowRetriesWithoutMaximumAttempts() {
        this.timedOutAttempt();
        this.advance(10);
        this.timedOutAttempt();
        this.advance(20);

        assertEquals(TwoFactorPolicy.Outcome.RETRY, this.timedOutAttempt());
        assertEquals(300, this.policy.getRetryDelaySeconds());
        assertEquals("2FA retry 1 in 300 s (attempt 3, 0 of 43200 s)", this.last());
        assertTrue(this.policy.isPastFastAttempts());

        this.advance(300);
        assertEquals(TwoFactorPolicy.Outcome.RETRY, this.timedOutAttempt());
        assertEquals("2FA retry 2 in 300 s (attempt 4, 450 of 43200 s)", this.last());
        assertEquals(0, this.count("maximum attempts reached"));
    }

    @Test
    public void aHangWithNoCloseFiresTheTimerForTheOpenAttemptOnly() {
        int attempt = this.policy.onWindowOpened();
        this.advance(TwoFactorPolicy.TIMEOUT_SECONDS);

        assertFalse(this.policy.claimTimeoutCancel(attempt - 1));
        assertTrue(this.policy.claimTimeoutCancel(attempt));
        assertEquals("Closing 2FA window after timeout (attempt 1, 150 s)", this.last());

        this.policy.onTimeoutCancelNotFound();
        assertEquals("2FA Cancel button not found (attempt 1, 150 s)", this.last());
    }

    @Test
    public void aTimerAfterTheCloseClicksNothing() {
        int attempt = this.policy.onWindowOpened();
        this.advance(30);
        this.policy.onWindowClosed();
        this.advance(120);

        assertFalse(this.policy.claimTimeoutCancel(attempt));
        assertEquals(0, this.count("Closing 2FA window after timeout"));
    }

    @Test
    public void theDefaultRetryWindowEndsTwelveHoursAfterTheThirdAttempt() {
        int attempts = this.attemptsUntilExhausted();

        assertEquals(99, attempts);
        assertEquals(96, this.count("2FA retry "));
        assertEquals("2FA retries exhausted (attempt 99, 96 slow retries in 43200 s)", this.lines.get(this.lines.size() - 2));
        assertEquals("2FA maximum attempts reached", this.last());
        assertEquals(1, this.count("2FA maximum attempts reached"));
    }

    @Test
    public void aOneHourRetryWindowEndsAfterEightSlowRetries() {
        this.policy = this.newPolicy(1);
        int attempts = this.attemptsUntilExhausted();

        assertEquals(11, attempts);
        assertEquals(8, this.count("2FA retry "));
        assertEquals("2FA maximum attempts reached", this.last());
    }

    @Test
    public void retryHoursZeroStopsAfterThreeAttemptsAsUpstreamDoes() {
        this.policy = this.newPolicy(0);
        int attempts = this.attemptsUntilExhausted();

        assertEquals(3, attempts);
        assertEquals(0, this.count("2FA retry "));
        assertEquals("2FA maximum attempts reached", this.last());
    }

    @Test
    public void anApprovalDuringTheSlowRetriesStartsOver() {
        this.timedOutAttempt();
        this.advance(10);
        this.timedOutAttempt();
        this.advance(20);
        this.timedOutAttempt();
        this.advance(300);

        this.policy.onWindowOpened();
        this.advance(50);
        assertEquals(TwoFactorPolicy.Outcome.APPROVED, this.policy.onWindowClosed());
        assertEquals("2FA confirmation success after 50 s (attempt 4)", this.last());
        assertEquals(1, this.count("2FA confirmation success"));
        assertFalse(this.policy.isPastFastAttempts());

        this.lines.clear();
        assertEquals(TwoFactorPolicy.Outcome.RETRY, this.timedOutAttempt());
        assertEquals(10, this.policy.getRetryDelaySeconds());
        assertTrue(this.lines.contains("twoFactorConfirmationAttempts: 1/3"));
    }

    @Test
    public void aStrayCloseIsNotASuccess() {
        assertEquals(TwoFactorPolicy.Outcome.IGNORED, this.policy.onWindowClosed());
        assertEquals("2FA window closed with no attempt open", this.last());
        assertEquals(0, this.count("2FA confirmation success"));
    }

    private TwoFactorPolicy newPolicy(int retryHours) {
        return new TwoFactorPolicy(TwoFactorPolicy.DEFAULT_SLOW_RETRY_SECONDS, retryHours, () -> this.nowMillis, this.lines::add);
    }

    private TwoFactorPolicy.Outcome timedOutAttempt() {
        int attempt = this.policy.onWindowOpened();
        this.advance(TwoFactorPolicy.TIMEOUT_SECONDS);
        assertTrue(this.policy.claimTimeoutCancel(attempt));
        return this.policy.onWindowClosed();
    }

    private int attemptsUntilExhausted() {
        int attempts = 0;
        TwoFactorPolicy.Outcome outcome;
        do {
            outcome = this.timedOutAttempt();
            attempts++;
            if (outcome == TwoFactorPolicy.Outcome.RETRY) {
                this.advance(this.policy.getRetryDelaySeconds());
            }
        } while (outcome == TwoFactorPolicy.Outcome.RETRY && attempts < 1000);
        assertEquals(TwoFactorPolicy.Outcome.EXHAUSTED, outcome);
        return attempts;
    }

    private void advance(long seconds) {
        this.nowMillis += seconds * 1000;
    }

    private String last() {
        return this.lines.get(this.lines.size() - 1);
    }

    private int count(String text) {
        int n = 0;
        for (String line : this.lines) {
            if (line.contains(text)) {
                n++;
            }
        }
        return n;
    }
}
