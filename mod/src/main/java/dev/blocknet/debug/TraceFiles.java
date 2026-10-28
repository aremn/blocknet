// SPDX-License-Identifier: MIT
package dev.blocknet.debug;

import dev.blocknet.Blocknet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Appends trace lines to {@code <game dir>/logs/blocknet/<bot>-trace.log} on a background thread, so
 * the server thread never waits for the disk. Each game launch starts a fresh file and keeps the
 * previous one as {@code <bot>-trace.1.log}; a file over 32 MB rolls over the same way.
 */
public final class TraceFiles {
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private record Line(String bot, String text) {}

    private static final LinkedBlockingQueue<Line> QUEUE = new LinkedBlockingQueue<>(100_000);
    private static final Map<String, BufferedWriter> WRITERS = new HashMap<>();
    private static final Map<String, Long> SIZES = new HashMap<>();
    private static final Set<String> STARTED = new HashSet<>();
    private static volatile boolean warned;
    private static Thread thread;

    private TraceFiles() {}

    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve("blocknet");
    }

    public static Path file(String bot) {
        return directory().resolve(bot + "-trace.log");
    }

    static void append(String bot, String text) {
        start();
        // A full queue means the disk cannot keep up; drop rather than stall the game.
        QUEUE.offer(new Line(bot, text));
    }

    private static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(TraceFiles::run, "blocknet-trace-writer");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        while (true) {
            try {
                Line line = QUEUE.poll(1, TimeUnit.SECONDS);
                if (line == null) {
                    flushAll();
                    continue;
                }
                write(line);
                // Drain what is already queued before flushing once.
                Line next;
                while ((next = QUEUE.poll()) != null) write(next);
                flushAll();
            } catch (InterruptedException exception) {
                flushAll();
                return;
            } catch (RuntimeException exception) {
                warnOnce(exception);
            }
        }
    }

    private static void write(Line line) {
        try {
            BufferedWriter writer = writer(line.bot());
            writer.write(line.text());
            writer.newLine();
            long size = SIZES.merge(line.bot(), (long) line.text().length() + 1, Long::sum);
            if (size > MAX_BYTES) {
                writer.close();
                WRITERS.remove(line.bot());
                roll(line.bot());
                SIZES.put(line.bot(), 0L);
            }
        } catch (IOException exception) {
            warnOnce(exception);
        }
    }

    private static BufferedWriter writer(String bot) throws IOException {
        BufferedWriter writer = WRITERS.get(bot);
        if (writer != null) return writer;
        Files.createDirectories(directory());
        if (STARTED.add(bot)) roll(bot);
        Path file = file(bot);
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        WRITERS.put(bot, writer);
        SIZES.put(bot, Files.exists(file) ? Files.size(file) : 0L);
        return writer;
    }

    private static void roll(String bot) throws IOException {
        Path file = file(bot);
        if (Files.exists(file)) {
            Files.move(file, directory().resolve(bot + "-trace.1.log"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void flushAll() {
        for (BufferedWriter writer : WRITERS.values()) {
            try {
                writer.flush();
            } catch (IOException exception) {
                warnOnce(exception);
            }
        }
    }

    private static void warnOnce(Exception exception) {
        if (warned) return;
        warned = true;
        Blocknet.LOGGER.warn("Blocknet trace file could not be written", exception);
    }
}
