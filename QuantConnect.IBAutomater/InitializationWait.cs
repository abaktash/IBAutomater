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

namespace QuantConnect.IBAutomater
{
    /// <summary>
    /// The wait for the IB Gateway login and configuration: 15 minutes from its start, moved to 15 minutes after
    /// each 2FA progress line from the Java agent, never past the start plus 15 minutes plus the 2FA retry hours.
    /// </summary>
    internal sealed class InitializationWait
    {
        /// <summary>
        /// The wait with no 2FA in progress, upstream's initialization timeout
        /// </summary>
        public static readonly TimeSpan BaseTimeout = TimeSpan.FromMinutes(15);

        private readonly Func<DateTime> _utcNow;
        private readonly Action<string> _log;
        private readonly object _locker = new object();

        private bool _active;
        private DateTime _start;
        private DateTime _deadline;
        private DateTime _cap;

        /// <summary>
        /// Creates a new instance of the <see cref="InitializationWait"/> class
        /// </summary>
        /// <param name="utcNow">The UTC clock</param>
        /// <param name="log">The log line writer</param>
        public InitializationWait(Func<DateTime> utcNow, Action<string> log)
        {
            _utcNow = utcNow;
            _log = log;
        }

        /// <summary>
        /// Starts a wait
        /// </summary>
        /// <param name="retryHours">How long past the first 15 minutes 2FA progress may keep the wait open; 0 never extends it</param>
        public void Begin(double retryHours)
        {
            lock (_locker)
            {
                _start = _utcNow();
                _deadline = _start + BaseTimeout;
                _cap = _deadline + TimeSpan.FromHours(retryHours);
                _active = true;
            }
        }

        /// <summary>
        /// Takes a line from the Java agent; a 2FA progress line moves the deadline
        /// </summary>
        /// <param name="text">The line</param>
        public void OnLine(string text)
        {
            if (!IsTwoFactorProgressLine(text))
            {
                return;
            }

            DateTime deadline;
            lock (_locker)
            {
                if (!_active)
                {
                    return;
                }

                deadline = _utcNow() + BaseTimeout;
                if (deadline > _cap)
                {
                    deadline = _cap;
                }
                if (deadline <= _deadline)
                {
                    return;
                }
                _deadline = deadline;
            }

            _log($"IBAutomater initialization wait extended to {deadline:yyyy-MM-ddTHH:mm:ssZ} (2FA in progress)");
        }

        /// <summary>
        /// Waits until the deadline, which progress lines may move while it waits
        /// </summary>
        /// <param name="waitOne">Waits for the initialization event for at most the given time; returns true if it was set</param>
        /// <returns>Returns true if the initialization event was set, false if the deadline passed</returns>
        public bool Wait(Func<TimeSpan, bool> waitOne)
        {
            while (true)
            {
                TimeSpan remaining;
                string capLine = null;
                lock (_locker)
                {
                    var now = _utcNow();
                    remaining = _deadline - now;
                    if (remaining <= TimeSpan.Zero)
                    {
                        _active = false;
                        if (_cap > _start + BaseTimeout && _deadline >= _cap)
                        {
                            capLine = $"IBAutomater initialization wait cap reached after {(long)(now - _start).TotalSeconds} s";
                        }
                    }
                }

                if (remaining <= TimeSpan.Zero)
                {
                    if (capLine != null)
                    {
                        _log(capLine);
                    }
                    return false;
                }

                if (waitOne(remaining))
                {
                    lock (_locker)
                    {
                        _active = false;
                    }
                    return true;
                }
            }
        }

        /// <summary>
        /// Whether a line from the Java agent shows a 2FA attempt in progress
        /// </summary>
        public static bool IsTwoFactorProgressLine(string text)
        {
            if (string.IsNullOrEmpty(text))
            {
                return false;
            }

            return (text.Contains("Second Factor Authentication", StringComparison.InvariantCultureIgnoreCase)
                    && text.Contains("[WINDOW_OPENED]", StringComparison.InvariantCultureIgnoreCase))
                || text.Contains("New login attempt with 2FA", StringComparison.InvariantCultureIgnoreCase)
                || text.Contains("2FA retry ", StringComparison.InvariantCultureIgnoreCase);
        }
    }
}
