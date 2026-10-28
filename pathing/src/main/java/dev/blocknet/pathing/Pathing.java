/*
 * This file is part of Blocknet's pathing module, derived from Baritone/Automatone.
 *
 * It is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 */

package dev.blocknet.pathing;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.Item;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.common.Tags;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * What the extracted pathfinder needs from its host, in place of PlayerEngine's static members.
 */
public final class Pathing {
    public static final String MOD_ID = "blocknet_ai";
    public static final String MOD_NAME = "Blocknet pathing";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    /** NeoForge common tags instead of PlayerEngine's own bucket tags. */
    public static final TagKey<Item> EMPTY_BUCKETS = Tags.Items.BUCKETS_EMPTY;
    public static final TagKey<Item> WATER_BUCKETS = Tags.Items.BUCKETS_WATER;

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    /** Background path calculation; daemon threads so a stopping server is never held up. */
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
        runnable -> {
            Thread thread = new Thread(runnable, "blocknet-pathing-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

    /** Receives the pathfinder's own explanations (debug and chat lines) for an entity. */
    @FunctionalInterface
    public interface Trace {
        void log(net.minecraft.world.entity.LivingEntity entity, String message);
    }

    private static volatile Trace trace = (entity, message) -> {};

    private Pathing() {}

    public static ThreadPoolExecutor getExecutor() {
        return EXECUTOR;
    }

    /**
     * The host decides where explanations go. Server players without a client (the bot) cannot
     * read chat, so without this hook every "why" of the pathfinder was lost.
     */
    public static void setTrace(Trace sink) {
        trace = sink == null ? (entity, message) -> {} : sink;
    }

    /** May be called from the calculation threads; the sink must be thread-safe. */
    public static void trace(net.minecraft.world.entity.LivingEntity entity, String message) {
        try {
            trace.log(entity, message);
        } catch (RuntimeException exception) {
            LOGGER.debug("Pathing trace sink failed", exception);
        }
    }
}
