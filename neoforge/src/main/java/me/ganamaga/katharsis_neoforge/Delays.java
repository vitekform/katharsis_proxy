package me.ganamaga.katharsis_neoforge;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/** Runs things a number of server ticks from now, on the server thread. */
final class Delays {

    private record Due(long tick, Runnable task) {
    }

    private final List<Due> due = new ArrayList<>();
    private long ticks;

    /** Only from the server thread. */
    void later(int delayTicks, Runnable task) {
        due.add(new Due(ticks + delayTicks, task));
    }

    void onTick(ServerTickEvent.Post event) {
        ticks++;
        if (due.isEmpty()) {
            return;
        }
        List<Runnable> now = new ArrayList<>();
        due.removeIf(entry -> entry.tick() <= ticks && now.add(entry.task()));
        // Outside the loop, so a task may schedule another.
        now.forEach(Runnable::run);
    }
}
