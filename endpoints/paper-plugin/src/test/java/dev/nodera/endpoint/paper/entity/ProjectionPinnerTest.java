package dev.nodera.endpoint.paper.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin, over a simulated hour, with no server anywhere ([L-69], server task 5 deliverable 6).
 *
 * <p>Everything L-69's exit clause claims is asserted here as arithmetic, so the live stage is a
 * confirmation rather than the only evidence: an item that does not despawn, a drift bound that is
 * a number, and a credit that happens once however many actors reach for it.
 *
 * <p>The fake item is deliberately hostile. It ages and it keeps moving after its velocity has been
 * zeroed, which is what a real projection does: the platform integrates the entity BEFORE the region
 * task runs, so zeroing velocity afterwards cannot undo the tick that already happened, and gravity
 * puts the velocity straight back on the next one.
 */
class ProjectionPinnerTest {

    /** An hour at 20 TPS — twelve times vanilla's despawn age. */
    private static final int ONE_HOUR_TICKS = 72_000;

    @Test
    void aPinnedItemSurvivesAnHourThatWouldHaveDespawnedItTwelveTimesOver() {
        FakeItem item = new FakeItem();
        FakeTicker ticker = new FakeTicker();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> true, message -> { });

        pinner.pin(item);
        ticker.run(ONE_HOUR_TICKS);

        assertThat(ONE_HOUR_TICKS).isGreaterThan(ProjectionPinner.VANILLA_DESPAWN_TICKS);
        assertThat(item.age).isEqualTo(1);
        assertThat(item.removed).isFalse();
        assertThat(pinner.isPinned(item.id())).isTrue();
        assertThat(pinner.faults()).isZero();
    }

    @Test
    void driftStaysInsideTheStatedBoundEvenThoughTheItemKeepsMovingBetweenTicks() {
        // 0.4 blocks per tick on every axis at once is worse than an item at terminal fall speed,
        // and it never stops — the pin is given no help at all.
        FakeItem item = new FakeItem().driftingBy(0.4, -0.4, 0.4);
        FakeTicker ticker = new FakeTicker();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> true, message -> { });

        pinner.pin(item);
        ticker.run(ONE_HOUR_TICKS);

        assertThat(pinner.maxDriftObserved())
                .isLessThanOrEqualTo(ProjectionPinner.MAX_OBSERVED_DRIFT_BLOCKS);
        assertThat(pinner.maxDriftObserved())
                .isGreaterThan(ProjectionPinner.DRIFT_TOLERANCE_BLOCKS);
    }

    @Test
    void anItemThatSettlesStopsBeingTeleported() {
        // The realistic shape: an item falls for two seconds and then rests on a block. If the pin
        // wrote the position back on every tick regardless — the implementation that would make the
        // test above trivially pass — this is where it would show, as client-visible jitter on an
        // item that is not moving at all.
        FakeItem item = new FakeItem().driftingBy(0, -0.4, 0).settlingAfter(40);
        FakeTicker ticker = new FakeTicker();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> true, message -> { });

        pinner.pin(item);
        ticker.run(ONE_HOUR_TICKS);

        assertThat(pinner.reanchors()).isLessThanOrEqualTo(40);
        assertThat(item.moves).isEqualTo((int) pinner.reanchors());
    }

    @Test
    void aStationaryItemIsNeverTeleported() {
        FakeItem item = new FakeItem();
        FakeTicker ticker = new FakeTicker();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> true, message -> { });

        pinner.pin(item);
        ticker.run(ONE_HOUR_TICKS);

        assertThat(pinner.reanchors()).isZero();
        assertThat(item.moves).isZero();
    }

    @Test
    void everyTickDeniesEveryVanillaActorTheCredit() {
        FakeItem item = new FakeItem();
        FakeTicker ticker = new FakeTicker();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> true, message -> { });

        pinner.pin(item);
        item.pickupDelay = 0;       // a foreign plugin clears it
        item.canMobPickup = true;   // and re-enables mob pickup
        ticker.run(1);

        assertThat(item.pickupDelay).isEqualTo(ProjectionPinner.NEVER_PICK_UP);
        assertThat(item.canMobPickup).isFalse();
    }

    @Test
    void tenTakersReachingTheSameItemCreditExactlyOnce() {
        FakeItem item = new FakeItem();
        FakeTicker ticker = new FakeTicker();
        AtomicInteger credits = new AtomicInteger();
        ProjectionPinner pinner = new ProjectionPinner(
                ticker, (i, t) -> credits.incrementAndGet() > 0, message -> { });

        pinner.pin(item);
        List<Boolean> answers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            answers.add(pinner.claim(item.id(), UUID.randomUUID()));
        }

        assertThat(credits.get()).isEqualTo(1);
        assertThat(answers).containsExactly(true, false, false, false, false,
                false, false, false, false, false);
        assertThat(item.removed).isTrue();
        assertThat(pinner.isPinned(item.id())).isFalse();
    }

    /**
     * The case the claim ledger exists for, and the one the test above does not reach.
     *
     * <p>Nine of those ten takers were refused because the credit had already unpinned the item, not
     * because anything remembered the claim — so a pinner with no ledger at all would pass it. The
     * hole it leaves is re-entrancy: a lane that, while crediting, fires something a listener turns
     * straight back into a pickup would find the item still pinned and credit it twice. Ordering
     * cannot fix that, because both calls are on the same thread and the second is inside the first.
     */
    @Test
    void aReentrantClaimFromInsideTheLaneIsRefused() {
        FakeItem item = new FakeItem();
        FakeTicker ticker = new FakeTicker();
        AtomicInteger credits = new AtomicInteger();
        ProjectionPinner[] holder = new ProjectionPinner[1];
        holder[0] = new ProjectionPinner(ticker, (i, t) -> {
            credits.incrementAndGet();
            // Inside the credit, before it has finished: the item is still pinned here.
            assertThat(holder[0].claim(i, UUID.randomUUID())).isFalse();
            return true;
        }, message -> { });

        assertThat(holder[0].isPinned(item.id())).isFalse();
        holder[0].pin(item);
        assertThat(holder[0].claim(item.id(), UUID.randomUUID())).isTrue();

        assertThat(credits.get()).isEqualTo(1);
        assertThat(holder[0].refusedDuplicateClaims()).isEqualTo(1);
    }

    @Test
    void aLaneThatDeclinesLeavesTheItemClaimableAgain() {
        FakeItem item = new FakeItem();
        FakeTicker ticker = new FakeTicker();
        AtomicInteger attempts = new AtomicInteger();
        // Busy for the first two intents and taking the third — a region mid-commit is a refusal,
        // not an error, and burning the claim on it would make one refusal permanent.
        ProjectionPinner pinner = new ProjectionPinner(
                ticker, (i, t) -> attempts.incrementAndGet() >= 3, message -> { });

        pinner.pin(item);
        assertThat(pinner.claim(item.id(), UUID.randomUUID())).isFalse();
        assertThat(pinner.claim(item.id(), UUID.randomUUID())).isFalse();
        assertThat(pinner.claim(item.id(), UUID.randomUUID())).isTrue();

        assertThat(pinner.refusedByLane()).isEqualTo(2);
        assertThat(pinner.refusedDuplicateClaims()).isZero();
        assertThat(item.removed).isTrue();
    }

    @Test
    void aTakerInRangeIsRoutedFromTheRegionTickWithNoBukkitEventAtAll() {
        UUID taker = UUID.randomUUID();
        FakeItem item = new FakeItem().withTakerInRange(taker);
        FakeTicker ticker = new FakeTicker();
        List<UUID> credited = new ArrayList<>();
        ProjectionPinner pinner = new ProjectionPinner(
                ticker, (i, t) -> credited.add(t), message -> { });

        pinner.pin(item);
        ticker.run(20);

        // Once, not twenty times: the item leaves the registry with the first credit, so the
        // remaining nineteen ticks find nothing to route.
        assertThat(credited).containsExactly(taker);
    }

    @Test
    void aThrowingProjectionDropsOneItemAndNothingElse() {
        FakeItem healthy = new FakeItem();
        FakeItem poisoned = new FakeItem().throwingOnVelocityAfter(2);
        FakeTicker ticker = new FakeTicker();
        List<String> logged = new ArrayList<>();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> true, logged::add);

        pinner.pin(healthy);
        pinner.pin(poisoned);
        ticker.run(10);

        assertThat(pinner.isPinned(poisoned.id())).isFalse();
        assertThat(pinner.isPinned(healthy.id())).isTrue();
        assertThat(pinner.faults()).isEqualTo(1);
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0)).contains(poisoned.id().toString());
    }

    @Test
    void anItemThatLeftTheWorldIsUnpinnedRatherThanWrittenTo() {
        FakeItem item = new FakeItem();
        FakeTicker ticker = new FakeTicker();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> true, message -> { });

        pinner.pin(item);
        item.valid = false;
        ticker.run(5);

        assertThat(pinner.isPinned(item.id())).isFalse();
        assertThat(pinner.faults()).isZero();
        assertThat(ticker.cancelled).isEqualTo(1);
    }

    @Test
    void theSummaryNamesEveryCounterAnOperatorNeeds() {
        FakeItem item = new FakeItem().driftingBy(0.6, 0, 0);
        FakeTicker ticker = new FakeTicker();
        ProjectionPinner pinner = new ProjectionPinner(ticker, (i, t) -> false, message -> { });

        pinner.pin(item);
        ticker.run(3);
        pinner.claim(item.id(), UUID.randomUUID());

        assertThat(pinner.summary())
                .contains("1 pinned")
                .contains("re-anchored")
                .contains("max drift")
                .contains("1 declined by the lane")
                .contains("0 contained fault(s)");
    }

    // -----------------------------------------------------------------------------------------

    /** A repeating-task scheduler with no threads in it: {@link #run} is the tick clock. */
    private static final class FakeTicker implements ProjectionPinner.RegionTicker {

        private record Task(ProjectionPinner.Projection projection, Runnable body) { }

        private final List<Task> tasks = new ArrayList<>();
        private int cancelled;

        @Override
        public AutoCloseable everyTick(ProjectionPinner.Projection projection, Runnable body) {
            Task task = new Task(projection, body);
            tasks.add(task);
            return () -> {
                tasks.remove(task);
                cancelled++;
            };
        }

        /** One platform tick: integrate every entity, then run every region task. */
        void run(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                for (Task task : List.copyOf(tasks)) {
                    ((FakeItem) task.projection()).advance();
                    task.body().run();
                }
            }
        }
    }

    /** An item that ages, moves, and can be told to misbehave. */
    private static final class FakeItem implements ProjectionPinner.Projection {

        private final UUID id = UUID.randomUUID();
        private double x;
        private double y = 64;
        private double z;
        private double dx;
        private double dy;
        private double dz;
        private int settleAfter = Integer.MAX_VALUE;
        private int ticks;
        private int age;
        private int pickupDelay;
        private boolean canMobPickup = true;
        private boolean valid = true;
        private boolean removed;
        private int moves;
        private int throwOnVelocityAfter = Integer.MAX_VALUE;
        private int velocityWrites;
        private UUID takerInRange;

        /** Displacement the platform applies each tick, whatever the pin wrote last tick. */
        FakeItem driftingBy(double perTickX, double perTickY, double perTickZ) {
            this.dx = perTickX;
            this.dy = perTickY;
            this.dz = perTickZ;
            return this;
        }

        /** After this many ticks the item comes to rest, as one landing on a block does. */
        FakeItem settlingAfter(int tick) {
            this.settleAfter = tick;
            return this;
        }

        FakeItem withTakerInRange(UUID taker) {
            this.takerInRange = taker;
            return this;
        }

        FakeItem throwingOnVelocityAfter(int writes) {
            this.throwOnVelocityAfter = writes;
            return this;
        }

        /** What the platform does before the region task runs. */
        void advance() {
            ticks++;
            age++;
            if (ticks > settleAfter) {
                return;
            }
            x += dx;
            y += dy;
            z += dz;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public double[] position() {
            return new double[] {x, y, z};
        }

        @Override
        public void resetLifetime() {
            age = 1;
        }

        @Override
        public void zeroVelocity() {
            if (++velocityWrites > throwOnVelocityAfter) {
                throw new IllegalStateException("the platform refused a velocity write");
            }
        }

        @Override
        public void denyVanillaPickup() {
            pickupDelay = ProjectionPinner.NEVER_PICK_UP;
            canMobPickup = false;
        }

        @Override
        public void moveTo(double toX, double toY, double toZ) {
            x = toX;
            y = toY;
            z = toZ;
            moves++;
        }

        @Override
        public Optional<UUID> nearestTakerWithin(double radius) {
            return Optional.ofNullable(takerInRange);
        }

        @Override
        public void remove() {
            removed = true;
            valid = false;
        }
    }
}
