package buffeffects.hytale.window;

import buffeffects.core.json.Json;
import buffeffects.core.window.WindowModel;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The BuffEffects window as a raw {@code .ui} page (CommandDeck's pattern). All state and layout live in the
 * game-neutral {@link WindowModel}; this class builds the page, decodes the client's events, and sends the
 * model's updates through {@link HytaleRenderer}.
 */
public final class EditorPage extends InteractiveCustomUIPage<EditorPage.NoEvent> {
    /** Events are decoded from the raw JSON, so the typed codec is never used. */
    public static final class NoEvent {
        static final BuilderCodec<NoEvent> CODEC = BuilderCodec.builder(NoEvent.class, NoEvent::new).build();
    }

    static final long TYPING_DELAY_MS = 300;

    private final WindowModel model;
    private final Consumer<String> log;
    private volatile ScheduledFuture<?> pendingRedraw;
    private volatile boolean closed;

    EditorPage(PlayerRef player, WindowModel model, Consumer<String> log) {
        super(player, CustomPageLifetime.CanDismiss, NoEvent.CODEC);
        this.model = model;
        this.log = log;
    }

    /** Opens the page for a player, on the player's world thread. False if the player is not in a world. */
    public static boolean open(PlayerRef player, WindowModel model, Consumer<String> log) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return false;
        World world = ref.getStore().getExternalData().getWorld();
        Runnable r = () -> {
            Ref<EntityStore> now = player.getReference();
            if (now == null || !now.isValid()) return;
            Store<EntityStore> store = now.getStore();
            Player p = store.getComponent(now, Player.getComponentType());
            if (p != null) p.getPageManager().openCustomPage(now, store, new EditorPage(player, model, log));
        };
        if (world.isInThread()) r.run();
        else buffeffects.hytale.Worlds.run(world, r);
        return true;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append(HytaleRenderer.DOC);
        UiOps ops;
        synchronized (model) {
            ops = HytaleRenderer.render(model.open());
        }
        UiTranslate.into(ops, cmd, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String rawData) {
        Map<String, Object> data;
        try {
            Object parsed = Json.parse(rawData);
            if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("not an object");
            data = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) data.put(String.valueOf(e.getKey()), e.getValue());
        } catch (RuntimeException bad) {
            log.accept("undecodable window event " + rawData);
            return;
        }
        Object a = data.remove("Action");
        String action = a == null ? null : a.toString();
        Map<String, Object> event = HytaleRenderer.event(data);
        UiOps ops;
        boolean defer;
        try {
            synchronized (model) {
                ops = HytaleRenderer.render(model.onEvent(action, event));
                defer = action != null && WindowModel.deferred(action) && !(action.equals("field") && !ops.isEmpty());
            }
        } catch (RuntimeException e) {
            log.accept("the window failed on " + action + ": " + e);
            return;
        }
        if (defer) {
            // Each keystroke sends an event, and every page update must be acknowledged before the client
            // sends the next one. Redraw once typing pauses, so keystrokes aren't dropped.
            ScheduledFuture<?> old = pendingRedraw;
            if (old != null) old.cancel(false);
            String act = action;
            pendingRedraw = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                UiOps later;
                synchronized (model) {
                    later = HytaleRenderer.render(model.deferredOps(act));
                }
                send(later);
            }, TYPING_DELAY_MS, TimeUnit.MILLISECONDS);
            return;
        }
        send(ops);
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        closed = true;
        ScheduledFuture<?> f = pendingRedraw;
        if (f != null) f.cancel(false);
        super.onDismiss(ref, store);
    }

    private void send(UiOps ops) {
        // A late redraw must never land on another page the player opened since.
        if (closed || ops == null || ops.isEmpty()) return;
        try {
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder ev = new UIEventBuilder();
            UiTranslate.into(ops, cmd, ev);
            sendUpdate(cmd, ev, false);
        } catch (RuntimeException e) {
            log.accept("window update failed: " + e);
        }
    }
}
