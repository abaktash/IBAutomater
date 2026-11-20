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

using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using NUnit.Framework;

namespace QuantConnect.IBAutomater.Tests
{
    [TestFixture]
    public class InitializationWaitTests
    {
        private const string WindowOpened = "Window event: [WINDOW_OPENED] - Window title: [Second Factor Authentication] - Window name: [dialog0]";
        private const string FastRetry = "New login attempt with 2FA in 10 s (attempt 1)";
        private const string SlowRetry = "2FA retry 1 in 300 s (attempt 3, 0 of 43200 s)";

        private static readonly DateTime Start = new DateTime(2026, 11, 29, 22, 0, 0, DateTimeKind.Utc);

        private DateTime _now;
        private List<string> _lines;
        private InitializationWait _wait;

        [SetUp]
        public void SetUp()
        {
            _now = Start;
            _lines = new List<string>();
            _wait = new InitializationWait(() => _now, _lines.Add);
        }

        [Test]
        public void WithNoTwoFactorLineTheWaitEndsAtFifteenMinutes()
        {
            _wait.Begin(12);

            Assert.That(_wait.Wait(Script(new List<(double, string)>(), null)), Is.False);
            Assert.That(_now, Is.EqualTo(Start.AddMinutes(15)));
            Assert.That(_lines, Is.Empty);
        }

        [Test]
        public void AChainOfTwoFactorLinesKeepsTheWaitOpenUntilTheLogin()
        {
            _wait.Begin(12);
            var events = Every(300, 2 * 3600, SlowRetry);

            Assert.That(_wait.Wait(Script(events, 2 * 3600 + 10)), Is.True);
            Assert.That(_now, Is.EqualTo(Start.AddSeconds(2 * 3600 + 10)));
            Assert.That(_lines.Count(l => l.StartsWith("IBAutomater initialization wait extended to ")), Is.EqualTo(events.Count));
            Assert.That(_lines.Last(), Is.EqualTo("IBAutomater initialization wait extended to 2026-11-30T00:15:00Z (2FA in progress)"));
            Assert.That(_lines.Any(l => l.Contains("cap reached")), Is.False);
        }

        [Test]
        public void EachKindOfTwoFactorLineExtendsTheWait()
        {
            _wait.Begin(12);
            var events = new List<(double, string)> { (600, WindowOpened), (1200, FastRetry), (1800, SlowRetry) };

            Assert.That(_wait.Wait(Script(events, null)), Is.False);
            Assert.That(_now, Is.EqualTo(Start.AddSeconds(1800 + 900)));
            Assert.That(_lines, Is.EqualTo(new[]
            {
                "IBAutomater initialization wait extended to 2026-11-29T22:25:00Z (2FA in progress)",
                "IBAutomater initialization wait extended to 2026-11-29T22:35:00Z (2FA in progress)",
                "IBAutomater initialization wait extended to 2026-11-29T22:45:00Z (2FA in progress)",
            }));
        }

        [Test]
        public void AnAgentThatGoesSilentStillEndsFifteenMinutesAfterItsLastLine()
        {
            _wait.Begin(12);
            var events = Every(300, 3000, SlowRetry);

            Assert.That(_wait.Wait(Script(events, null)), Is.False);
            Assert.That(_now, Is.EqualTo(Start.AddSeconds(3000 + 900)));
            Assert.That(_lines.Any(l => l.Contains("cap reached")), Is.False);
        }

        [Test]
        public void TheCapEndsTheWait()
        {
            _wait.Begin(1);

            Assert.That(_wait.Wait(Script(Every(300, 20 * 3600, SlowRetry), null)), Is.False);
            Assert.That(_now, Is.EqualTo(Start.AddMinutes(75)));
            Assert.That(_lines.Last(), Is.EqualTo("IBAutomater initialization wait cap reached after 4500 s"));
            Assert.That(_lines, Does.Contain("IBAutomater initialization wait extended to 2026-11-29T23:15:00Z (2FA in progress)"));
        }

        [Test]
        public void RetryHoursZeroNeverExtendsTheWait()
        {
            _wait.Begin(0);

            Assert.That(_wait.Wait(Script(Every(60, 3600, SlowRetry), null)), Is.False);
            Assert.That(_now, Is.EqualTo(Start.AddMinutes(15)));
            Assert.That(_lines, Is.Empty);
        }

        [Test]
        public void OtherLinesDoNotExtendTheWait()
        {
            _wait.Begin(12);
            var events = new List<(double, string)>
            {
                (600, "2FA confirmation timeout after 150 s (attempt 1)"),
                (700, "Window event: [WINDOW_CLOSED] - Window title: [Second Factor Authentication] - Window name: [dialog0]"),
                (800, "2FA maximum attempts reached"),
                (850, "Click button: [Re-login]"),
            };

            Assert.That(_wait.Wait(Script(events, null)), Is.False);
            Assert.That(_now, Is.EqualTo(Start.AddMinutes(15)));
            Assert.That(_lines, Is.Empty);
        }

        [Test]
        public void LinesOutsideAWaitAreIgnored()
        {
            _wait.OnLine(SlowRetry);
            _wait.Begin(12);
            Assert.That(_wait.Wait(Script(new List<(double, string)>(), 60)), Is.True);

            _now = _now.AddSeconds(30);
            _wait.OnLine(SlowRetry);
            Assert.That(_lines, Is.Empty);
        }

        [Test]
        public void MaximumAttemptsEndsTheStartAtOnce()
        {
            var automater = CreateAutomater();

            automater.OnProcessOutputDataReceived("2FA maximum attempts reached");

            Assert.That(automater.GetLastStartResult().ErrorCode, Is.EqualTo(ErrorCode.TwoFactorConfirmationTimeout));
        }

        [Test]
        public void TheKnobsDefaultToTwelveHoursAndThreeHundredSeconds()
        {
            var automater = CreateAutomater();

            Assert.That(automater.TwoFactorRetryHours, Is.EqualTo(12));
            Assert.That(automater.TwoFactorRetrySlowSeconds, Is.EqualTo(300));
        }

        [TestCase(0)]
        [TestCase(0.5)]
        [TestCase(24)]
        public void RetryHoursInRangeAreKept(double hours)
        {
            var automater = CreateAutomater();
            automater.TwoFactorRetryHours = hours;

            Assert.That(automater.TwoFactorRetryHours, Is.EqualTo(hours));
        }

        [TestCase(-0.01)]
        [TestCase(24.01)]
        [TestCase(double.NaN)]
        [TestCase(double.PositiveInfinity)]
        public void RetryHoursOutOfRangeAreRefused(double hours)
        {
            var automater = CreateAutomater();

            Assert.Throws<ArgumentException>(() => automater.TwoFactorRetryHours = hours);
            Assert.That(automater.TwoFactorRetryHours, Is.EqualTo(12));
        }

        [TestCase(60)]
        [TestCase(3600)]
        public void SlowSecondsInRangeAreKept(int seconds)
        {
            var automater = CreateAutomater();
            automater.TwoFactorRetrySlowSeconds = seconds;

            Assert.That(automater.TwoFactorRetrySlowSeconds, Is.EqualTo(seconds));
        }

        [TestCase(59)]
        [TestCase(3601)]
        [TestCase(0)]
        public void SlowSecondsOutOfRangeAreRefused(int seconds)
        {
            var automater = CreateAutomater();

            Assert.Throws<ArgumentException>(() => automater.TwoFactorRetrySlowSeconds = seconds);
            Assert.That(automater.TwoFactorRetrySlowSeconds, Is.EqualTo(300));
        }

        [TestCase(12, 300, "IBAutomater 2FA policy: retry-hours=12 slow-seconds=300 initialization-wait=15 min, extended while 2FA continues, cap 735 min")]
        [TestCase(0, 300, "IBAutomater 2FA policy: retry-hours=0 slow-seconds=300 initialization-wait=15 min, extended while 2FA continues, cap 15 min")]
        [TestCase(0.5, 120, "IBAutomater 2FA policy: retry-hours=0.5 slow-seconds=120 initialization-wait=15 min, extended while 2FA continues, cap 45 min")]
        public void ThePolicyLine(double hours, int seconds, string expected)
        {
            var automater = CreateAutomater();
            automater.TwoFactorRetryHours = hours;
            automater.TwoFactorRetrySlowSeconds = seconds;

            Assert.That(automater.TwoFactorPolicyLine(), Is.EqualTo(expected));
        }

        [Test]
        public void TheJvmArgumentsUseTheInvariantCulture()
        {
            var culture = Thread.CurrentThread.CurrentCulture;
            try
            {
                Thread.CurrentThread.CurrentCulture = new CultureInfo("de-DE");
                var automater = CreateAutomater();
                automater.TwoFactorRetryHours = 0.5;
                automater.TwoFactorRetrySlowSeconds = 120;

                Assert.That(automater.TwoFactorJvmArguments(),
                    Is.EqualTo("-J-Dibautomater.twofa.retryHours=0.5 -J-Dibautomater.twofa.slowRetrySeconds=120"));
            }
            finally
            {
                Thread.CurrentThread.CurrentCulture = culture;
            }
        }

        [Test]
        public void TheJvmArgumentsCarryTheDefaults()
        {
            Assert.That(CreateAutomater().TwoFactorJvmArguments(),
                Is.EqualTo("-J-Dibautomater.twofa.retryHours=12 -J-Dibautomater.twofa.slowRetrySeconds=300"));
        }

        private Func<TimeSpan, bool> Script(List<(double Seconds, string Line)> events, double? signalSeconds)
        {
            var pending = new Queue<(double Seconds, string Line)>(events.OrderBy(e => e.Seconds));
            return timeout =>
            {
                var end = _now + timeout;
                var signal = signalSeconds.HasValue ? Start.AddSeconds(signalSeconds.Value) : DateTime.MaxValue;
                while (pending.Count > 0 && Start.AddSeconds(pending.Peek().Seconds) <= end && Start.AddSeconds(pending.Peek().Seconds) < signal)
                {
                    var next = pending.Dequeue();
                    _now = Start.AddSeconds(next.Seconds);
                    _wait.OnLine(next.Line);
                }
                if (signal <= end)
                {
                    _now = signal;
                    return true;
                }
                _now = end;
                return false;
            };
        }

        private static List<(double, string)> Every(int periodSeconds, int untilSeconds, string line)
        {
            var events = new List<(double, string)>();
            for (var t = periodSeconds; t <= untilSeconds; t += periodSeconds)
            {
                events.Add((t, line));
            }
            return events;
        }

        private static IBAutomater CreateAutomater()
        {
            return new IBAutomater("/nonexistent", "0", "user", "password", "paper", 4002, false);
        }
    }
}
