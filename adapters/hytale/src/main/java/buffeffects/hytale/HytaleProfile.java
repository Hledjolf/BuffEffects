package buffeffects.hytale;

import buffeffects.core.format.GameProfile;
import buffeffects.core.format.NativeEffect;
import buffeffects.core.format.Part;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.modelvfx.config.ModelVFX;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Hytale 0.7.5 for the loader: what the adapter can do, and which of the game's ids exist (live asset maps). */
final class HytaleProfile implements GameProfile {
    /** Neutral cause names added because a matching DamageCause is registered ({@code shadow} for {@code Shadow}). */
    final Set<String> extra = ConcurrentHashMap.newKeySet();

    public String name() {
        return "hytale";
    }

    public boolean supportsWorldClock() {
        return true;
    }

    public boolean supports(Part p) {
        // Hytale's MovementEffects has no crouch lock.
        if (p.type().equals("lock_movement") && p.bool("crouch", false) && !p.bool("all", false)) return false;
        return !p.type().equals("behavior");
    }

    public boolean supportsAbsorb() {
        return false;
    }

    public Set<String> extraCauses() {
        return extra;
    }

    /** Neutral names for causes registered by mods or the server (P12): any DamageCause beyond the vanilla vocabulary. */
    void scanCauses() {
        try {
            for (String id : DamageCause.getAssetMap().getAssetMap().keySet()) {
                String n = id.toLowerCase(Locale.ROOT);
                if (n.matches("[a-z0-9_]+")) extra.add(n);
            }
        } catch (RuntimeException ignored) {
            // asset maps not ready: no extra causes
        }
    }

    public Boolean knows(Kind kind, String id) {
        try {
            switch (kind) {
                case EFFECT:
                    return EntityEffect.getAssetMap().getAsset(id) != null;
                case MODEL_VFX:
                    return ModelVFX.getAssetMap().getAsset(id) != null;
                case CAUSE:
                    return DamageCause.getAssetMap().getAsset(id) != null;
                case STAT:
                    return EntityStatType.getAssetMap().getAsset(id) != null;
                case PARTICLE:
                    return ParticleSystem.getAssetMap().getAsset(id) != null;
                case SOUND:
                    return SoundEvent.getAssetMap().getAsset(id) != null;
                case MODEL:
                    return ModelAsset.getAssetMap().getAsset(id) != null;
                default:
                    return null; // icons, screen effects, animations: textures and model data the server can't list
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String assetName(String fullId) {
        return "BFX_" + Assets.sanitize(fullId);
    }

    /** Translations of the game's own effects, read from the loaded assets (effects.md section 9.1). */
    private final Map<String, java.util.Optional<NativeEffect>> natives = new ConcurrentHashMap<>();

    @Override
    public NativeEffect nativeEffect(String id) {
        if (id.startsWith("BFX_")) return null; // BuffEffects' own generated assets
        return natives.computeIfAbsent(id, k -> {
            try {
                EntityEffect e = EntityEffect.getAssetMap().getAsset(k);
                return java.util.Optional.ofNullable(e == null ? null : NativeTranslator.translate(k, NativeTranslator.toJson(e), extra));
            } catch (RuntimeException | LinkageError ex) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    /** Forgets the translations, after the game's effect assets change. */
    void nativesChanged() {
        natives.clear();
    }

}
