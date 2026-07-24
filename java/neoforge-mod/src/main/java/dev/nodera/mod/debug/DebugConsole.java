package dev.nodera.mod.debug;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The in-game Nodera debug console ({@code /nodera debug verbose on|off}): while enabled for a
 * player, every Nodera service log line (loggers named {@code Nodera*} or under
 * {@code dev.nodera}) is mirrored to that player's chat, so a live two-client session can be
 * debugged from inside the game — no terminal tailing.
 *
 * <p>Mechanism: a programmatic log4j appender on the root logger config captures matching events
 * into a bounded queue (log4j threads must never touch Minecraft), and the server tick drains
 * the queue to every subscribed player on the main thread. When the last subscriber leaves, the
 * appender is detached.
 *
 * <p>Thread-context: {@link #subscribe}/{@link #unsubscribe} on the server thread;
 * {@link #flush} on the server tick; the appender's {@code append} on log4j's threads.
 */
public final class DebugConsole {

    /** Bounded backlog — a log storm must degrade to dropped lines, never unbounded memory. */
    private static final int MAX_QUEUED = 200;

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final Queue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static final Map<String, ChatFormatting> LEVEL_COLORS = Map.of(
            "ERROR", ChatFormatting.RED,
            "WARN", ChatFormatting.YELLOW,
            "INFO", ChatFormatting.GRAY,
            "DEBUG", ChatFormatting.DARK_GRAY);
    private static final AtomicBoolean ATTACHED = new AtomicBoolean();
    private static volatile NoderaLogAppender appender;

    private DebugConsole() {
    }

    /** Turn the console on for {@code player}; attaches the log appender on first subscriber. */
    public static void subscribe(ServerPlayer player) {
        SUBSCRIBERS.add(player.getUUID());
        if (ATTACHED.compareAndSet(false, true)) {
            NoderaLogAppender a = new NoderaLogAppender();
            a.start();
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().addAppender(a, Level.DEBUG, null);
            ctx.updateLoggers();
            appender = a;
        }
    }

    /** Turn the console off for {@code player}; detaches the appender when nobody listens. */
    public static void unsubscribe(ServerPlayer player) {
        SUBSCRIBERS.remove(player.getUUID());
        detachIfIdle();
    }

    /** @return whether {@code player} currently receives the console stream. */
    public static boolean subscribed(UUID player) {
        return SUBSCRIBERS.contains(player);
    }

    /** Server-tick drain: deliver captured lines to every subscribed online player. */
    public static void flush(MinecraftServer server) {
        if (SUBSCRIBERS.isEmpty() || PENDING.isEmpty()) {
            PENDING.clear();
            return;
        }
        String line;
        int delivered = 0;
        while (delivered < 20 && (line = PENDING.poll()) != null) { // ≤20 lines/tick — no chat flood
            delivered++;
            ChatFormatting color = colorOf(line);
            Component chat = Component.literal("[Nodera] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(line).withStyle(color));
            for (UUID id : SUBSCRIBERS) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) {
                    p.sendSystemMessage(chat);
                }
            }
        }
    }

    /** Push a synthetic console line (the relay-metrics summary uses this). */
    public static void push(String line) {
        if (!SUBSCRIBERS.isEmpty()) {
            offer(line);
        }
    }

    private static ChatFormatting colorOf(String line) {
        for (Map.Entry<String, ChatFormatting> e : LEVEL_COLORS.entrySet()) {
            if (line.startsWith(e.getKey() + " ")) {
                return e.getValue();
            }
        }
        return ChatFormatting.GRAY;
    }

    private static void offer(String line) {
        if (PENDING.size() >= MAX_QUEUED) {
            PENDING.poll(); // drop oldest — the console is a window, not a journal
        }
        PENDING.add(line);
    }

    private static void detachIfIdle() {
        if (!SUBSCRIBERS.isEmpty() || !ATTACHED.compareAndSet(true, false)) {
            return;
        }
        NoderaLogAppender a = appender;
        appender = null;
        if (a != null) {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeAppender(a.getName());
            ctx.updateLoggers();
            a.stop();
        }
        PENDING.clear();
    }

    /** Captures Nodera-logger events off log4j's threads into the bounded queue. */
    private static final class NoderaLogAppender extends AbstractAppender {

        NoderaLogAppender() {
            super("NoderaDebugConsole", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String logger = event.getLoggerName();
            if (logger == null
                    || (!logger.startsWith("Nodera") && !logger.startsWith("dev.nodera"))) {
                return;
            }
            String rendered = event.getLevel().name() + " [" + shortName(logger) + "] "
                    + event.getMessage().getFormattedMessage();
            offer(rendered);
        }

        private static String shortName(String logger) {
            int dot = logger.lastIndexOf('.');
            return dot >= 0 ? logger.substring(dot + 1) : logger;
        }
    }
}
