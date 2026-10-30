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
using NodaTime;
using NUnit.Framework;

namespace QuantConnect.IBAutomater.Tests
{
    [TestFixture]
    public class ServerResetWindowTests
    {
        private static readonly DateTimeZone NewYork = DateTimeZoneProviders.Tzdb["America/New_York"];

        [TestCase(2026, 10, 28, 23, 45, true)]
        [TestCase(2026, 10, 29, 0, 59, true)]
        [TestCase(2026, 10, 29, 1, 0, false)]
        [TestCase(2026, 10, 29, 1, 30, false)]
        [TestCase(2026, 10, 28, 23, 30, false)]
        [TestCase(2026, 10, 28, 12, 0, false)]
        public void DefaultWindowIsUpstreams(int year, int month, int day, int hour, int minute, bool expected)
        {
            var automater = CreateAutomater();

            Assert.That(automater.ServerResetWindowEt, Is.EqualTo("23:30-01:00"));
            Assert.That(automater.IsWithinScheduledServerResetTimes(NewYorkToUtc(year, month, day, hour, minute)), Is.EqualTo(expected));
        }

        [TestCase(2026, 10, 29, 1, 30, true)]
        [TestCase(2026, 10, 29, 1, 44, true)]
        [TestCase(2026, 10, 29, 1, 45, false)]
        [TestCase(2026, 10, 28, 23, 31, true)]
        [TestCase(2026, 11, 5, 1, 30, true)]
        [TestCase(2026, 11, 5, 2, 0, false)]
        public void ConfiguredWindowReplacesTheAmericaWindow(int year, int month, int day, int hour, int minute, bool expected)
        {
            var automater = CreateAutomater();
            automater.ServerResetWindowEt = "23:30-01:45";

            Assert.That(automater.ServerResetWindowEt, Is.EqualTo("23:30-01:45"));
            Assert.That(automater.IsWithinScheduledServerResetTimes(NewYorkToUtc(year, month, day, hour, minute)), Is.EqualTo(expected));
        }

        [TestCase(0, 10, false)]
        [TestCase(0, 20, true)]
        [TestCase(1, 50, false)]
        public void WindowWithinOneDay(int hour, int minute, bool expected)
        {
            var automater = CreateAutomater();
            automater.ServerResetWindowEt = "00:15-01:45";

            Assert.That(automater.IsWithinScheduledServerResetTimes(NewYorkToUtc(2026, 10, 29, hour, minute)), Is.EqualTo(expected));
        }

        [Test]
        public void WeekendWindowIsUnchanged()
        {
            var automater = CreateAutomater();
            automater.ServerResetWindowEt = "00:15-01:45";

            Assert.That(automater.IsWithinScheduledServerResetTimes(NewYorkToUtc(2026, 10, 31, 12, 0)), Is.True);
        }

        [TestCase(null)]
        [TestCase("")]
        [TestCase("23:30")]
        [TestCase("23:30-")]
        [TestCase("25:00-01:00")]
        [TestCase("23:30-23:30")]
        [TestCase("23.30-01.45")]
        [TestCase("23:30-01:45-02:00")]
        public void MalformedWindowIsRefused(string value)
        {
            var automater = CreateAutomater();

            Assert.Throws<ArgumentException>(() => automater.ServerResetWindowEt = value);
            Assert.That(automater.ServerResetWindowEt, Is.EqualTo("23:30-01:00"));
        }

        private static IBAutomater CreateAutomater()
        {
            return new IBAutomater("/nonexistent", "0", "user", "password", "paper", 4002, false);
        }

        private static DateTime NewYorkToUtc(int year, int month, int day, int hour, int minute)
        {
            return NewYork.AtStrictly(new LocalDateTime(year, month, day, hour, minute)).ToDateTimeUtc();
        }
    }
}
