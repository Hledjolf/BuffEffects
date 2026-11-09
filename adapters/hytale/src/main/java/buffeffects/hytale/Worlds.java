package buffeffects.hytale;

import com.hypixel.hytale.server.core.universe.world.World;

/** Running work on a world's thread. */
public final class Worlds {
    private Worlds() {
    }

    /**
     * Queues {@code r} on a world's thread, unless the world is stopping. A stopping world runs what's left in
     * its queue on the shutdown thread, where every store read throws, and the shutdown then never finishes
     * (SC-016, 2026-11-07). So nothing is queued into a world that isn't alive, and a task that finds itself
     * off the world's thread does nothing.
     */
    public static void run(World w, Runnable r) {
        if (!w.isAlive()) return;
        w.execute(() -> {
            if (w.isAlive() && w.isInThread()) r.run();
        });
    }
}
