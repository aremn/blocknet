// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.function.Consumer;

/** Composite actions retain one owner/reporter while using the ordinary controller tasks. */
abstract class SkillTask {
    final BotPlayer bot;
    final BotController controller;
    final Consumer<String> report;
    boolean busy, done;
    int ticks;
    final int budget;

    SkillTask(BotPlayer bot, Consumer<String> report, int budget) {
        this.bot = bot; controller = bot.controller(); this.report = report; this.budget = budget;
    }
    final void tick() {
        if (done) return;
        if (++ticks >= budget) { complete("stopped: budget ended; " + describe()); return; }
        if (ticks % 200 == 0) dev.blocknet.bridge.BridgeServer.taskProgress(bot, describe());
        work();
    }
    abstract void work();
    abstract String describe();
    final void action(Consumer<Consumer<String>> start, Consumer<String> after) {
        busy = true;
        controller.skillAction(() -> start.accept(message -> {
            if (done) return;
            busy = false;
            after.accept(message);
        }));
    }
    final void complete(String message) {
        if (done) return;
        done = true;
        controller.skillFinished(this, message, report);
    }
    static boolean failed(String message) {
        return message.contains("failed:") || message.contains("blocked:") || message.contains("stopped")
            || message.startsWith("none seen") || message.startsWith("unsupported:");
    }
}
