package com.siaws.siawsmod.content.agents;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A workshop agent: a cheap humanoid body for the LLM-driven villager of the design brief
 * (mdocs/wiki/reference/agent-embodiment-observation.md, pillar 1, option C). Vanilla-style
 * goals do the low-level steering; the LLM will only pick *what* to do at decision points.
 *
 * <p>Player-gated interactions (break/place/use) will go through a NeoForge FakePlayer
 * delegate — see {@link AgentInteractionDelegate}, which is deliberately not wired yet.
 */
public class WorkshopAgentEntity extends PathfinderMob {
    public static final int INVENTORY_SIZE = 27;

    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_UUID =
            SynchedEntityData.defineId(WorkshopAgentEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> DATA_TASK_LABEL =
            SynchedEntityData.defineId(WorkshopAgentEntity.class, EntityDataSerializers.STRING);

    /** Last activity label set via {@link #setActivityLabel}; cache only, never persisted.
     *  Null until the first set so the initial application always happens ("" would no-op). */
    @Nullable
    private String activityLabel = null;

    /** Agent storage; the observation snapshot renders an inventory summary from this. */
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private final AgentInteractionDelegate interactionDelegate = new AgentInteractionDelegate(this);

    public WorkshopAgentEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 16.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.8, 0.01F));
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    /**
     * The task label shown in the observation snapshot: the board key of the task this agent
     * currently claims (set by the decision loop), or "idle". Synched to clients; session-
     * scoped — not persisted, so a reloaded world starts agents at "idle" while the board's
     * lease sweep cleans up stale claims.
     */
    public String currentTask() {
        String label = this.entityData.get(DATA_TASK_LABEL);
        return label.isEmpty() ? "idle" : label;
    }

    /** Sets the claimed-task label ("" = idle). Called by the decision loop. */
    public void setCurrentTaskLabel(String label) {
        this.entityData.set(DATA_TASK_LABEL, label == null ? "" : label);
    }

    /**
     * Human-readable activity state for the nameplate (see {@link AgentActivity}): the
     * loop sets it at state transitions. Cached so repeat sets with the same value are
     * no-ops — the executor ticks far more often than the state moves. Session-scoped:
     * the custom name is cleared on world load, never persisted.
     */
    public void setActivityLabel(@Nullable String label) {
        String next = label == null ? "" : label;
        if (java.util.Objects.equals(next, this.activityLabel)) {
            return;
        }
        this.activityLabel = next;        Component name = getType().getDescription();
        if (!next.isEmpty()) {
            name = name.copy().append(Component.literal(" · " + next));
        }
        setCustomName(name);
        setCustomNameVisible(true);
    }

    /**
     * The seam where a FakePlayer-backed delegate will later perform break/place/use so
     * player-gated code paths work (design pillar 1, option C). Not wired yet.
     */
    public AgentInteractionDelegate interactionDelegate() {
        return interactionDelegate;
    }

    @Nullable
    public UUID getOwnerUUID() {
        return this.entityData.get(DATA_OWNER_UUID).orElse(null);
    }

    public void setOwnerUUID(@Nullable UUID owner) {
        this.entityData.set(DATA_OWNER_UUID, Optional.ofNullable(owner));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER_UUID, Optional.empty());
        builder.define(DATA_TASK_LABEL, "");
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false; // agents persist like villagers, not like monsters
    }

    @Override
    protected boolean shouldDropLoot() {
        return false; // no loot table for agents; the custom inventory drops in die() instead
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide) {
            Containers.dropContents(this.level(), this.blockPosition(), this.inventory);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = getOwnerUUID();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.put("Inventory", ContainerHelper.saveAllItems(new CompoundTag(), this.inventory.getItems(), this.registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        if (tag.contains("Inventory")) {
            ContainerHelper.loadAllItems(tag.getCompound("Inventory"), this.inventory.getItems(), this.registryAccess());
        }
        // the nameplate carries session-scoped activity state (AgentActivity); never persist it
        setCustomName(null);
        setCustomNameVisible(false);
        this.activityLabel = null;
    }
}
