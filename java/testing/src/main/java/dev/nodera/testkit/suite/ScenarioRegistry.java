package dev.nodera.testkit.suite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every scenario the tool knows about, discovered rather than listed.
 *
 * <h2>Why a service loader and not a hand-kept array</h2>
 *
 * <p>The shell runner carried an {@code ALL_SUITES=(…)} array, and a suite added without touching
 * it existed as a file nobody ran. Discovery through {@link ServiceLoader} makes "written" and
 * "runnable" the same state: a scenario appears in {@code list} the moment its provider entry
 * exists, and the ordering below is about what should run first, not about what exists.
 *
 * <h2>The default order is deliberate</h2>
 *
 * <p>Headless scenarios run before live ones. They need no display and no client, so a batch on a
 * machine without a GUI still proves the measurement and control planes before spending twenty
 * minutes on Minecraft — and if the control plane is broken, every live failure after it would have
 * been a symptom of that one.
 *
 * <p>Thread-context: build once, read many.
 */
public final class ScenarioRegistry {

    private final Map<String, Scenario> byId = new LinkedHashMap<>();

    private ScenarioRegistry(List<Scenario> scenarios) {
        List<Scenario> ordered = new ArrayList<>(scenarios);
        ordered.sort((a, b) -> {
            boolean aHeadless = a.tags().contains("headless");
            boolean bHeadless = b.tags().contains("headless");
            if (aHeadless != bHeadless) {
                return aHeadless ? -1 : 1;
            }
            return a.id().compareTo(b.id());
        });
        for (Scenario scenario : ordered) {
            Scenario clash = byId.put(scenario.id(), scenario);
            if (clash != null) {
                throw new IllegalStateException("two scenarios claim the id '" + scenario.id()
                        + "': " + clash.getClass().getName() + " and " + scenario.getClass().getName());
            }
        }
    }

    /** Discover every scenario on the classpath. */
    public static ScenarioRegistry discover() {
        List<Scenario> found = new ArrayList<>();
        ServiceLoader.load(Scenario.class).forEach(found::add);
        return new ScenarioRegistry(found);
    }

    /** A registry over an explicit list — for the tool's own tests. */
    public static ScenarioRegistry of(List<Scenario> scenarios) {
        return new ScenarioRegistry(scenarios);
    }

    /** Every scenario, in default run order. */
    public List<Scenario> all() {
        return List.copyOf(byId.values());
    }

    /** Every scenario that is safe to run unattended: everything not tagged {@code hardware}. */
    public List<Scenario> defaultBatch() {
        return byId.values().stream().filter(s -> !s.tags().contains("hardware")).toList();
    }

    /** Scenarios carrying a tag. */
    public List<Scenario> withTag(String tag) {
        return byId.values().stream().filter(s -> s.tags().contains(tag)).toList();
    }

    /** Every tag in use, sorted — what {@code --tag} will accept. */
    public Set<String> tags() {
        Set<String> tags = new TreeSet<>();
        byId.values().forEach(scenario -> tags.addAll(scenario.tags()));
        return tags;
    }

    /**
     * Look one up by id.
     *
     * @throws IllegalArgumentException listing the known ids, because a typo that silently ran
     *                                  nothing is the worst possible answer to "run this test".
     */
    public Scenario require(String id) {
        Scenario scenario = byId.get(id);
        if (scenario == null) {
            throw new IllegalArgumentException("unknown scenario '" + id + "'. Known: "
                    + String.join(", ", byId.keySet()));
        }
        return scenario;
    }

    /** Resolve a list of ids, in the order given. */
    public List<Scenario> require(List<String> ids) {
        return ids.stream().map(this::require).toList();
    }
}
