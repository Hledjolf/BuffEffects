package buffeffects.hytale;

import buffeffects.core.engine.Engine;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The game events the engine needs: a hit (for {@code breaks_on_hit}) and a death (for {@code on_death}). */
final class Systems {
    private Systems() {
    }

    static String uuidOf(Store<EntityStore> store, Ref<EntityStore> ref) {
        UUIDComponent u = store.getComponent(ref, UUIDComponent.getComponentType());
        return u == null ? null : u.getUuid().toString();
    }

    /** After the damage filters (inspect group): damage that got through, with an amount, breaks regeneration. */
    static final class OnDamage extends DamageEventSystem {
        private final Engine engine;
        private final HytalePort port;

        OnDamage(Engine engine, HytalePort port) {
            this.engine = engine;
            this.port = port;
        }

        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getInspectDamageGroup();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return UUIDComponent.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> buffer, Damage damage) {
            if (damage.isCancelled() || damage.getAmount() <= 0) return;
            UUIDComponent u = chunk.getComponent(index, UUIDComponent.getComponentType());
            if (u == null) return;
            String id = u.getUuid().toString();
            if (port.targets.containsKey(id)) engine.onDamaged(id);
        }
    }

    /** A DeathComponent was added: the target died. */
    static final class OnDeath extends DeathSystems.OnDeathSystem {
        private final Engine engine;
        private final HytalePort port;

        OnDeath(Engine engine, HytalePort port) {
            this.engine = engine;
            this.port = port;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return UUIDComponent.getComponentType();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
            String id = uuidOf(store, ref);
            if (id == null || !port.targets.containsKey(id)) return;
            HytalePort.Tracked t = port.targets.get(id);
            t.alive = false;
            engine.onDeath(id);
        }
    }
}
