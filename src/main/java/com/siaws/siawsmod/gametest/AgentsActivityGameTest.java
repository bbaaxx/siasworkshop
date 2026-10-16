package com.siaws.siawsmod.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.siaws.siawsmod.content.agents.AgentActivity;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.init.SiasWorkshopEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * State-visibility tests (SEQ-015/016): the nameplate label composition, the activity
 * cache (repeat sets are no-ops), and the JSONL activity-log line format + append.
 */
@net.neoforged.neoforge.gametest.GameTestHolder("siasworkshop")
public final class AgentsActivityGameTest {
    private AgentsActivityGameTest() {}

    @GameTest(template = "empty")
    public static void activityLabelsRenderOnNameplate(GameTestHelper helper) {
        WorkshopAgentEntity agent = spawnAgent(helper);
        String base = agent.getType().getDescription().getString();

        helper.assertTrue(agent.getCustomName() == null, "fresh agent should have no custom name");
        AgentActivity.idle(agent);
        helper.assertTrue(agent.getCustomName().getString().equals(base),
                "idle label should render the bare type name: " + agent.getCustomName());

        AgentActivity.deciding(agent);
        helper.assertTrue(agent.getCustomName().getString().equals(base + " · deciding…"),
                "deciding label: " + agent.getCustomName());

        BoardTask build = new BoardTask(UUID.randomUUID().toString(), "build:mazons:0,0,0",
                BoardTask.Type.BUILD, BoardTask.Status.OPEN, null, 0, 0, 0, "blueprint=mazons anchor=0,0,0", null);
        AgentActivity.working(agent, build, "placing");
        helper.assertTrue(agent.getCustomName().getString().contains("building mazons · placing"),
                "working label with phase: " + agent.getCustomName());

        AgentActivity.working(agent, build, "placing"); // same value: must no-op without error
        helper.assertTrue(agent.getCustomName().getString().contains("building mazons · placing"),
                "repeat set must keep the label: " + agent.getCustomName());

        AgentActivity.resting(agent);
        helper.assertTrue(agent.getCustomName().getString().equals(base + " · resting"),
                "resting label: " + agent.getCustomName());
        agent.discard(); // the GameTest world persists between batches: never leave a live agent behind
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void activityLogLineFormatAndAppend(GameTestHelper helper) throws Exception {
        WorkshopAgentEntity agent = spawnAgent(helper);
        String line = AgentActivity.line(1234L, agent, "claim", Map.of("key", "comfort:light:x"));
        helper.assertTrue(line.contains("\"event\":\"claim\"") && line.contains("\"key\":\"comfort:light:x\""),
                "line must carry event and data: " + line);
        helper.assertTrue(line.contains("\"agent\":\"" + agent.getUUID().toString().substring(0, 8) + "\""),
                "line must carry the short agent id: " + line);

        Path dir = Files.createTempDirectory("siasworkshop-activity-test");
        Path file = dir.resolve("agent-activity.log");
        AgentActivity.appendLine(file, line);
        AgentActivity.appendLine(file, line);
        String read = Files.readString(file);
        helper.assertTrue(read.lines().count() == 2 && read.contains("\"ts\":1234"),
                "append must write one JSONL line per call: " + read);
        agent.discard(); // the GameTest world persists between batches: never leave a live agent behind
        helper.succeed();
    }

    private static WorkshopAgentEntity spawnAgent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = new WorkshopAgentEntity(SiasWorkshopEntities.WORKSHOP_AGENT.get(), level);
        BlockPos absolute = helper.absolutePos(new BlockPos(0, 1, 0));
        agent.moveTo(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(agent);
        helper.assertFalse(agent.isRemoved(), "agent failed to spawn");
        return agent;
    }
}
