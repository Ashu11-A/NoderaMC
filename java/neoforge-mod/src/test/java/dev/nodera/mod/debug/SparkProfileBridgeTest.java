package dev.nodera.mod.debug;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nodera.mod.debug.SparkProfileBridge.Action;
import dev.nodera.mod.debug.SparkProfileBridge.Countdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parts of the client profiling bridge that are decidable without a running game.
 *
 * <p>The dispatch itself needs a live client and is exercised by {@code scripts/e2e-profile.sh};
 * what is tested here is everything that decides <em>whether</em> and <em>when</em> to dispatch —
 * the flag parsing, the command construction, and the tick countdown. Those are where a mistake
 * would be silent: a bridge that never fires produces no artifact and no error.
 */
class SparkProfileBridgeTest {

    @Nested
    @DisplayName("configuredSeconds")
    class ConfiguredSeconds {

        @Test
        @DisplayName("an absent or blank property leaves the bridge disabled")
        void absentIsDisabled() {
            assertThat(SparkProfileBridge.configuredSeconds(null)).isZero();
            assertThat(SparkProfileBridge.configuredSeconds("")).isZero();
            assertThat(SparkProfileBridge.configuredSeconds("   ")).isZero();
        }

        @Test
        @DisplayName("a number of seconds arms the bridge, whitespace and all")
        void parsesSeconds() {
            assertThat(SparkProfileBridge.configuredSeconds("60")).isEqualTo(60);
            assertThat(SparkProfileBridge.configuredSeconds("  120 ")).isEqualTo(120);
        }

        @Test
        @DisplayName("garbage disables the bridge rather than failing the game")
        void garbageIsDisabled() {
            // A mistyped diagnostic flag must never stop a client from starting.
            assertThat(SparkProfileBridge.configuredSeconds("sixty")).isZero();
            assertThat(SparkProfileBridge.configuredSeconds("60s")).isZero();
            assertThat(SparkProfileBridge.configuredSeconds("-30")).isZero();
        }
    }

    @Nested
    @DisplayName("startCommand")
    class StartCommand {

        @Test
        @DisplayName("always names the threads explicitly")
        void alwaysNamesThreads() {
            // The platform default dumper has been observed to yield a capture containing no
            // threads at all; --thread * is what makes the capture real.
            assertThat(SparkProfileBridge.startCommand(null))
                    .isEqualTo("sparkc profiler start --thread * --interval 4");
        }

        @Test
        @DisplayName("adds the lag-spike threshold when one is configured")
        void addsTicksOver() {
            assertThat(SparkProfileBridge.startCommand("100"))
                    .isEqualTo("sparkc profiler start --thread * --interval 4 --only-ticks-over 100");
        }

        @Test
        @DisplayName("ignores a threshold that is blank, zero or not a number")
        void ignoresBadThreshold() {
            String plain = "sparkc profiler start --thread * --interval 4";
            assertThat(SparkProfileBridge.startCommand("")).isEqualTo(plain);
            assertThat(SparkProfileBridge.startCommand("0")).isEqualTo(plain);
            assertThat(SparkProfileBridge.startCommand("soon")).isEqualTo(plain);
        }
    }

    @Nested
    @DisplayName("Countdown")
    class CountdownTest {

        private static Action advance(Countdown countdown, int ticks) {
            Action seen = Action.NOTHING;
            for (int i = 0; i < ticks; i++) {
                Action action = countdown.tick();
                if (action != Action.NOTHING) {
                    seen = action;
                }
            }
            return seen;
        }

        @Test
        @DisplayName("does nothing at all until a world has been entered")
        void silentBeforeWorld() {
            Countdown countdown = new Countdown();
            countdown.arm(20);
            assertThat(advance(countdown, 500)).isEqualTo(Action.NOTHING);
        }

        @Test
        @DisplayName("starts only after the warm-up, so it does not profile world loading")
        void warmsUpFirst() {
            Countdown countdown = new Countdown();
            countdown.arm(20 * SparkProfileBridge.TICKS_PER_SECOND);
            countdown.onWorldEntered();

            assertThat(advance(countdown, SparkProfileBridge.WARMUP_TICKS - 1))
                    .as("nothing may happen during the warm-up")
                    .isEqualTo(Action.NOTHING);
            assertThat(countdown.tick()).isEqualTo(Action.START);
        }

        @Test
        @DisplayName("stops exactly one capture-duration after it started")
        void stopsAfterTheDuration() {
            int duration = 20 * SparkProfileBridge.TICKS_PER_SECOND;
            Countdown countdown = new Countdown();
            countdown.arm(duration);
            countdown.onWorldEntered();
            advance(countdown, SparkProfileBridge.WARMUP_TICKS);   // consumes the START

            assertThat(advance(countdown, duration - 1))
                    .as("the capture must still be running")
                    .isEqualTo(Action.NOTHING);
            assertThat(countdown.tick()).isEqualTo(Action.STOP);
        }

        @Test
        @DisplayName("fires exactly once — a re-login does not start a second capture")
        void firesOnce() {
            int duration = 5 * SparkProfileBridge.TICKS_PER_SECOND;
            Countdown countdown = new Countdown();
            countdown.arm(duration);
            countdown.onWorldEntered();
            advance(countdown, SparkProfileBridge.WARMUP_TICKS + duration);   // START then STOP

            countdown.onWorldEntered();
            assertThat(advance(countdown, SparkProfileBridge.WARMUP_TICKS + duration + 100))
                    .as("a finished countdown must stay finished")
                    .isEqualTo(Action.NOTHING);
        }

        @Test
        @DisplayName("a re-login mid-warm-up does not restart the clock")
        void reloginDoesNotResetAnActiveCapture() {
            int duration = 10 * SparkProfileBridge.TICKS_PER_SECOND;
            Countdown countdown = new Countdown();
            countdown.arm(duration);
            countdown.onWorldEntered();
            advance(countdown, SparkProfileBridge.WARMUP_TICKS);   // START consumed

            countdown.onWorldEntered();   // must be ignored: the capture is already running
            assertThat(advance(countdown, duration - 1)).isEqualTo(Action.NOTHING);
            assertThat(countdown.tick()).isEqualTo(Action.STOP);
        }

        @Test
        @DisplayName("disarming silences it permanently")
        void disarmIsFinal() {
            Countdown countdown = new Countdown();
            countdown.arm(20);
            countdown.onWorldEntered();
            countdown.disarm();
            assertThat(advance(countdown, 1000)).isEqualTo(Action.NOTHING);
        }
    }
}
