// SPDX-License-Identifier: MIT
package dev.blocknet.debug;

import dev.blocknet.body.BotPlayer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The bot's reasoning trace: what it decided and why, newest last.
 *
 * <p>Every body decision (task start and result, route attempts, aiming, mining, danger), the
 * pathfinder's own explanations and the brain's notes land here. The overlay shows the tail; the
 * whole stream goes to {@code logs/blocknet/<bot>-trace.log} so a session can be read afterwards.
 * Repeated lines are merged ("x37") instead of flooding. Thread-safe: the pathfinder writes from
 * its calculation threads.
 */
public final class BotTrace {
    public record Entry(long tick, long millis, String source, String text, int repeats) {}

    private static final int CAPACITY = 400;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final int STATE_EVERY_TICKS = 100;

    private static final java.util.Map<String, BotTrace> TRACES = new java.util.concurrent.ConcurrentHashMap<>();

    /** The current body: respawning replaces the BotPlayer object, the trace stays. */
    private volatile BotPlayer bot;
    private final String name;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long changes;
    private String lastState = "";
    private volatile String brain = "";
    private long lastStateTick = Long.MIN_VALUE;

    // Rolling per-tick cost of the bot's own work, in nanoseconds (server thread only).
    private long controllerNanos, visionNanos, pathingNanos, physicsNanos;
    private long worstTickNanos;
    private long worstTickAt;

    private BotTrace(BotPlayer bot) {
        this.bot = bot;
        this.name = bot.getGameProfile().getName();
    }

    /** One trace per bot name, kept across death, respawn and despawn within a game launch. */
    public static BotTrace of(BotPlayer bot) {
        BotTrace trace = TRACES.computeIfAbsent(bot.getGameProfile().getName().toLowerCase(Locale.ROOT), key -> new BotTrace(bot));
        trace.bot = bot;
        return trace;
    }

    /** Null-safe shortcut for code that may run without a bot (tests, helpers). */
    public static void note(BotPlayer bot, String source, String text) {
        if (bot != null) bot.trace().add(source, text);
    }

    public void add(String source, String text) {
        long tick = bot.getServer() == null ? 0 : bot.getServer().getTickCount();
        String line = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        if (line.length() > 600) line = line.substring(0, 600) + "...";
        String repeatedLine = null;
        String written;
        synchronized (this) {
            Entry last = entries.peekLast();
            if (last != null && last.source().equals(source) && last.text().equals(line)) {
                entries.removeLast();
                entries.addLast(new Entry(tick, System.currentTimeMillis(), source, line, last.repeats() + 1));
                changes++;
                // Long repeats still leave a mark in the file now and then.
                if ((last.repeats() + 1) % 100 != 0) return;
                written = format(tick, source, line + " (x" + (last.repeats() + 1) + ")");
            } else {
                if (last != null && last.repeats() > 1) {
                    repeatedLine = format(last.tick(), last.source(), "... repeated " + last.repeats() + " times in total");
                }
                entries.addLast(new Entry(tick, System.currentTimeMillis(), source, line, 1));
                while (entries.size() > CAPACITY) entries.removeFirst();
                changes++;
                written = format(tick, source, line);
            }
        }
        if (repeatedLine != null) TraceFiles.append(name, repeatedLine);
        TraceFiles.append(name, written);
    }

    private static String format(long tick, String source, String text) {
        return LocalDateTime.now().format(TIME) + " t=" + tick + " [" + source + "] " + text;
    }

    public synchronized List<Entry> last(int count) {
        List<Entry> result = new ArrayList<>(Math.min(count, entries.size()));
        int skip = Math.max(0, entries.size() - count);
        for (Entry entry : entries) {
            if (skip-- > 0) continue;
            result.add(entry);
        }
        return result;
    }

    /** The brain's latest plan/step line, shown on its own row of the overlay. */
    public String brain() {
        return brain;
    }

    /** A note from the brain process (planner, actor, critic) over the bridge. */
    public void brainNote(String source, String text) {
        String label = source.length() > 24 ? source.substring(0, 24) : source;
        if (label.equals("plan") || label.equals("step")) brain = text.length() > 200 ? text.substring(0, 200) + "..." : text;
        add(label, text);
    }

    /** Grows with every new or merged line; viewers resend only when it moved. */
    public synchronized long changes() {
        return changes;
    }

    /** Called once per bot tick with the time each part of the tick took. */
    public void tickCost(long controller, long vision, long pathing, long physics) {
        controllerNanos = smooth(controllerNanos, controller);
        visionNanos = smooth(visionNanos, vision);
        pathingNanos = smooth(pathingNanos, pathing);
        physicsNanos = smooth(physicsNanos, physics);
        long total = controller + vision + pathing + physics;
        long tick = bot.getServer() == null ? 0 : bot.getServer().getTickCount();
        if (total > worstTickNanos || tick - worstTickAt > 200) {
            worstTickNanos = total;
            worstTickAt = tick;
        }
        // A tick of the bot alone above 20 ms is a lag spike every player feels.
        if (total > 20_000_000L) {
            add("lag", String.format(Locale.ROOT, "bot tick took %.1f ms (controller %.1f, vision %.1f, pathing %.1f, physics %.1f) during: %s",
                total / 1e6, controller / 1e6, vision / 1e6, pathing / 1e6, physics / 1e6, bot.controller().describe()));
        }
    }

    private static long smooth(long average, long sample) {
        return average == 0 ? sample : average + (sample - average) / 10;
    }

    public String costText() {
        return String.format(Locale.ROOT, "tick %.2f ms (ctl %.2f, vis %.2f, path %.2f, phys %.2f), worst %.1f ms",
            (controllerNanos + visionNanos + pathingNanos + physicsNanos) / 1e6, controllerNanos / 1e6,
            visionNanos / 1e6, pathingNanos / 1e6, physicsNanos / 1e6, worstTickNanos / 1e6);
    }

    /**
     * A compact state line, written to the file when it changes and at most every five seconds,
     * so the trace file alone shows where the bot was and what it was doing between decisions.
     */
    public void state(String text) {
        long tick = bot.getServer() == null ? 0 : bot.getServer().getTickCount();
        if (text.equals(lastState) || tick - lastStateTick < STATE_EVERY_TICKS && lastStateTick != Long.MIN_VALUE) return;
        lastState = text;
        lastStateTick = tick;
        TraceFiles.append(name, format(tick, "state", text));
    }
}
