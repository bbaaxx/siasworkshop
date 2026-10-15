package com.siaws.siawsmod.gametest;

import java.util.UUID;

import com.siaws.siawsmod.content.agents.AgentObservation;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.init.SiasWorkshopEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Game tests for the agents v1 spike. Registered via {@link GameTestHolder}; discovered
 * because {@code neoforge.enabledGameTestNamespaces} includes {@code siasworkshop}.
 */
@GameTestHolder("siasworkshop")
public final class AgentsGameTest {
    private AgentsGameTest() {}

    @GameTest(template = "empty")
    public static void observationSnapshotHasAllSections(GameTestHelper helper) {
        WorkshopAgentEntity agent = spawnAgent(helper);

        String snapshot = AgentObservation.build(agent, 900);

        assertSection(helper, snapshot, "[SELF]");
        assertSection(helper, snapshot, "[PLAYERS]");
        assertSection(helper, snapshot, "[NEARBY BLOCKS]");
        assertSection(helper, snapshot, "[NEARBY ENTITIES]");
        assertSection(helper, snapshot, "[ENVIRONMENT]");
        assertSection(helper, snapshot, "[CAPABILITIES once working a task]");
        helper.assertTrue(snapshot.contains("move("),
                "CAPABILITIES once working a task should always contain at least move(x,z):\n" + snapshot);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ownerAndInventoryPersistThroughNbt(GameTestHelper helper) {
        WorkshopAgentEntity agent = spawnAgent(helper);
        UUID owner = UUID.randomUUID();
        agent.setOwnerUUID(owner);
        agent.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 12));

        ServerLevel level = helper.getLevel();
        CompoundTag tag = new CompoundTag();
        agent.saveWithoutId(tag);
        WorkshopAgentEntity reloaded = new WorkshopAgentEntity(SiasWorkshopEntities.WORKSHOP_AGENT.get(), level);
        reloaded.load(tag);

        helper.assertTrue(owner.equals(reloaded.getOwnerUUID()), "owner UUID lost through NBT round trip");
        helper.assertTrue(reloaded.getInventory().getItem(0).getCount() == 12, "inventory lost through NBT round trip");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void observationListsPlayersWhenPresent(GameTestHelper helper) {
        WorkshopAgentEntity agent = spawnAgent(helper);
        ServerLevel level = helper.getLevel();
        // makeMockPlayer() returns a plain Player that is never registered, so it would NOT appear
        // in level.players() and appendPlayers would still short-circuit. makeMockServerPlayerInLevel()
        // runs the real join path (PlayerList.placeNewPlayer), which registers the ServerPlayer in
        // the level's player list. Verified against neoforge-21.1.256 bytecode.
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            BlockPos near = helper.absolutePos(new BlockPos(2, 1, 0));
            player.moveTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0.0F, 0.0F);
            helper.assertTrue(level.players().contains(player),
                    "mock server player must appear in level.players()");

            String snapshot = AgentObservation.build(agent, 900);

            assertSection(helper, snapshot, "[PLAYERS]");
            helper.assertFalse(snapshot.contains("[PLAYERS] none"),
                    "PLAYERS section short-circuited even though a player is present:\n" + snapshot);
            // The [SELF] section also emits "hp=", so anchor the per-player formatting assertions
            // to the [PLAYERS] line itself.
            String playersLine = snapshot.substring(snapshot.indexOf("[PLAYERS]"),
                    snapshot.indexOf('\n', snapshot.indexOf("[PLAYERS]")));
            helper.assertTrue(playersLine.contains("test-mock-player@"),
                    "PLAYERS line should name the mock player with a distance:\n" + playersLine);
            helper.assertTrue(playersLine.contains("hp="),
                    "PLAYERS line missing per-player hp= (formatting path did not run):\n" + playersLine);
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    private static WorkshopAgentEntity spawnAgent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = new WorkshopAgentEntity(SiasWorkshopEntities.WORKSHOP_AGENT.get(), level);
        BlockPos spawn = helper.absolutePos(new BlockPos(0, 1, 0));
        agent.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(agent);
        helper.assertFalse(agent.isRemoved(), "agent failed to spawn");
        return agent;
    }

    private static void assertSection(GameTestHelper helper, String snapshot, String header) {
        helper.assertTrue(snapshot.contains(header), "snapshot missing section " + header + ":\n" + snapshot);
    }
}
