/**
 * The Minecraft-free observability + diagnostics core (Task 18).
 *
 * <p>One pipeline — <i>capture → {@link dev.nodera.diagnostics.model.TelemetrySnapshot} →
 * {@link dev.nodera.diagnostics.view.DiagnosticsView} → surface</i> — with capture and presentation
 * split by value types that carry no {@code net.minecraft.*} dependency. Counting, rate math,
 * zone classification, and report building are therefore fully unit-testable; only the thin
 * renderers in {@code neoforge-mod} ({@code dev.nodera.mod.debug.render}) touch Minecraft.
 *
 * <ul>
 *   <li>{@code metric} — {@link dev.nodera.diagnostics.metric.TrafficMeter} (bytes+frames per
 *       direction), {@link dev.nodera.diagnostics.metric.MessageCounters} (per-type tallies),
 *       {@link dev.nodera.diagnostics.metric.RateWindow} (rolling bytes/sec + msgs/sec).</li>
 *   <li>{@code model} — the immutable {@link dev.nodera.diagnostics.model.TelemetrySnapshot} and
 *       its sub-records (session, net, regions, entities, health, peer links).</li>
 *   <li>{@code state} — semantic colour <b>policy</b> enums ({@link dev.nodera.diagnostics.state.OwnershipState},
 *       {@link dev.nodera.diagnostics.state.Health}, {@link dev.nodera.diagnostics.state.Semantic}).</li>
 *   <li>{@code classify} — {@link dev.nodera.diagnostics.classify.ZoneClassifier}: world position →
 *       region → ownership state (pure, negative-coordinate safe).</li>
 *   <li>{@code source} — capture seams ({@link dev.nodera.diagnostics.source.DiagnosticsSource})
 *       populated by other modules; {@link dev.nodera.diagnostics.source.RegionOwnershipProvider} and
 *       {@link dev.nodera.diagnostics.source.EntityControlProvider} are no-op stubs until Tasks 6/12.</li>
 *   <li>{@code view} — the MC-free presentation intermediate
 *       ({@link dev.nodera.diagnostics.view.DiagnosticsView} → {@link dev.nodera.diagnostics.view.Panel}/
 *       {@link dev.nodera.diagnostics.view.Row}/{@link dev.nodera.diagnostics.view.Cell}) built by
 *       {@link dev.nodera.diagnostics.view.ViewBuilder}.</li>
 * </ul>
 *
 * <p>Thread-context: counters are {@link java.util.concurrent.atomic.LongAdder}; model records are
 * immutable; the collector is sampled from a single driver thread (server tick). See Task 18.
 */
package dev.nodera.diagnostics;
