package com.siaws.siawsmod.gametest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.siaws.siawsmod.content.agents.AgentDecisionLoop;
import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.build.Blueprint;
import com.siaws.siawsmod.content.agents.build.BlueprintLibrary;
import com.siaws.siawsmod.content.agents.build.BuildCard;
import com.siaws.siawsmod.content.agents.build.BuildCards;
import com.siaws.siawsmod.content.agents.build.BuildQueue;
import com.siaws.siawsmod.content.agents.build.SchemParser;
import com.siaws.siawsmod.content.agents.build.StructureNbtParser;
import com.siaws.siawsmod.content.agents.pool.AgentDecisionPool;
import com.siaws.siawsmod.content.agents.pool.PoolEntry;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.ComfortDetectors;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;
import com.siaws.siawsmod.content.llm.LlmActions;
import com.siaws.siawsmod.content.llm.LlmClient;
import com.siaws.siawsmod.content.llm.LlmPrompts;
import com.siaws.siawsmod.config.Config;
import com.siaws.siawsmod.init.SiasWorkshopEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Game tests for the LLM client + build-knowledge slice: own-format parsers, build-card
 * parsing, the LLM client against a loopback stub, LLM content parsing, and end-to-end loop
 * runs (light fix and
 * blueprint build) on the level's real board. Hermetic tests (parsers, cards, stub, board
 * API) share the default batch; the end-to-end loop tests pin day time and run in dedicated
 * batches, same pattern as the detector tests.
 */
@GameTestHolder("siasworkshop")
public final class AgentsLlmGameTest {
    private AgentsLlmGameTest() {}

    // --- parsers -----------------------------------------------------------------

    @GameTest(template = "empty")
    public static void schemV3RoundTrip(GameTestHelper helper) throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 3);
        root.putShort("Width", (short) 2);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        ListTag palette = new ListTag();
        palette.add(paletteEntry("minecraft:stone"));
        palette.add(paletteEntry("minecraft:torch"));
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[] {0x00, 0x01}); // varints: stone, torch (x-fastest)

        Path file = writeTempSchematic(helper, "v3", ".schem", root);
        Blueprint blueprint = SchemParser.parse(file);

        helper.assertTrue(blueprint.size().equals(new Vec3i(2, 1, 1)), "size: " + blueprint.size());
        helper.assertTrue(blueprint.placements().size() == 2, "placements: " + blueprint.placements());
        helper.assertTrue(blueprint.placements().get(0).blockId().equals("minecraft:stone")
                        && blueprint.placements().get(0).rel().equals(new BlockPos(0, 0, 0)),
                "first placement: " + blueprint.placements().get(0));
        helper.assertTrue(blueprint.placements().get(1).blockId().equals("minecraft:torch")
                        && blueprint.placements().get(1).rel().equals(new BlockPos(1, 0, 0)),
                "second placement: " + blueprint.placements().get(1));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void schemV2RoundTrip(GameTestHelper helper) throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 2);
        root.putShort("Length", (short) 1);
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:stone", 0);
        palette.putInt("minecraft:oak_planks", 1);
        root.put("Palette", palette);
        // (y*len+z)*wid+x ordering: y0 -> stone, y1 -> planks
        root.putByteArray("BlockData", new byte[] {0x00, 0x01});

        Path file = writeTempSchematic(helper, "v2", ".schem", root);
        Blueprint blueprint = SchemParser.parse(file);

        helper.assertTrue(blueprint.size().equals(new Vec3i(1, 2, 1)), "size: " + blueprint.size());
        helper.assertTrue(blueprint.placements().get(0).blockId().equals("minecraft:stone")
                        && blueprint.placements().get(0).rel().equals(new BlockPos(0, 0, 0))
                        && blueprint.placements().get(1).blockId().equals("minecraft:oak_planks")
                        && blueprint.placements().get(1).rel().equals(new BlockPos(0, 1, 0)),
                "placements: " + blueprint.placements());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void schemSkipsAirPlacements(GameTestHelper helper) throws Exception {
        // schematics store air explicitly; the parser must drop those entries (agents never
        // "place air"), keeping real blocks at their positions
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 3);
        root.putShort("Width", (short) 3);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        ListTag palette = new ListTag();
        palette.add(paletteEntry("minecraft:stone"));
        palette.add(paletteEntry("minecraft:air"));
        palette.add(paletteEntry("minecraft:torch"));
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[] {0x00, 0x01, 0x02}); // stone, AIR, torch

        Path file = writeTempSchematic(helper, "v3air", ".schem", root);
        Blueprint blueprint = SchemParser.parse(file);

        helper.assertTrue(blueprint.placements().size() == 2, "air entry must be skipped: " + blueprint.placements());
        helper.assertTrue(blueprint.placements().get(0).blockId().equals("minecraft:stone")
                        && blueprint.placements().get(0).rel().equals(new BlockPos(0, 0, 0))
                        && blueprint.placements().get(1).blockId().equals("minecraft:torch")
                        && blueprint.placements().get(1).rel().equals(new BlockPos(2, 0, 0)),
                "solids must survive at their positions: " + blueprint.placements());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void schemRejectsMalformed(GameTestHelper helper) throws Exception {
        CompoundTag root = new CompoundTag(); // no size, no palette, no data
        Path file = writeTempSchematic(helper, "broken", ".schem", root);
        boolean threw = false;
        try {
            SchemParser.parse(file);
        } catch (Exception e) {
            threw = e.getMessage() != null && e.getMessage().contains("broken");
        }
        helper.assertTrue(threw, "malformed .schem must fail naming the file");

        CompoundTag oversized = new CompoundTag();
        oversized.putInt("Version", 3);
        oversized.putShort("Width", (short) 500);
        oversized.putShort("Height", (short) 500);
        oversized.putShort("Length", (short) 500);
        ListTag palette = new ListTag();
        palette.add(paletteEntry("minecraft:stone"));
        oversized.put("Palette", palette);
        oversized.putByteArray("BlockData", new byte[] {0});
        boolean rejected = false;
        try {
            SchemParser.parse(writeTempSchematic(helper, "huge", ".schem", oversized));
        } catch (Exception e) {
            rejected = e.getMessage() != null && e.getMessage().contains("exceeds cap");
        }
        helper.assertTrue(rejected, "oversized schematic must be rejected by the volume cap");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void structureNbtRoundTrip(GameTestHelper helper) throws Exception {
        CompoundTag root = new CompoundTag();
        root.putIntArray("size", new int[] {2, 1, 1});
        ListTag palette = new ListTag();
        palette.add(paletteEntry("minecraft:stone"));
        palette.add(paletteEntry("minecraft:torch"));
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(structureBlock(new BlockPos(0, 0, 0), 0));
        blocks.add(structureBlock(new BlockPos(1, 0, 0), 1));
        root.put("blocks", blocks);

        Path file = writeTempSchematic(helper, "tower", ".nbt", root);
        Blueprint blueprint = StructureNbtParser.parse(file);

        helper.assertTrue(blueprint.size().equals(new Vec3i(2, 1, 1)), "size: " + blueprint.size());
        helper.assertTrue(blueprint.placements().size() == 2
                        && blueprint.placements().get(1).blockId().equals("minecraft:torch")
                        && blueprint.placements().get(1).rel().equals(new BlockPos(1, 0, 0)),
                "placements: " + blueprint.placements());
        helper.succeed();
    }

    // --- build cards -----------------------------------------------------------------

    @GameTest(template = "empty")
    public static void buildCardParse(GameTestHelper helper) {
        String valid = """
                {"name":"watchtower","style_tags":["defense","stone"],"size":"7x18x7",
                 "materials_list":{"minecraft:stone":128},"palette":["minecraft:stone"],
                 "step_summary":"solid shaft, crenellated top","source_note":"own design"}
                """;
        BuildCard card = BuildCards.parseCard(valid, "test:watchtower");
        helper.assertTrue(card.name().equals("watchtower") && card.size().equals(new Vec3i(7, 18, 7))
                        && card.materialsList().get("minecraft:stone") == 128
                        && card.styleTags().contains("defense"),
                "valid card parsed wrong: " + card);

        String objectSize = "{\"name\":\"hut\",\"style_tags\":[\"village\"],\"size\":{\"w\":5,\"h\":4,\"l\":5},"
                + "\"materials_list\":{},\"palette\":[],\"step_summary\":\"\",\"source_note\":\"\"}";
        helper.assertTrue(BuildCards.parseCard(objectSize, "test:hut").size().equals(new Vec3i(5, 4, 5)),
                "object size form should parse");

        boolean rejected = false;
        try {
            BuildCards.parseCard("{\"style_tags\":[]}", "test:noname");
        } catch (IllegalArgumentException e) {
            rejected = e.getMessage().contains("test:noname");
        }
        helper.assertTrue(rejected, "card without a name must be rejected, naming the source");
        helper.succeed();
    }

    // --- LLM client against a loopback stub -------------------------------------------

    @GameTest(template = "empty")
    public static void llmClientAgainstStub(GameTestHelper helper) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> mode = new AtomicReference<>("ok");
        AtomicReference<String> lastRequestBody = new AtomicReference<>("");
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            String body;
            switch (mode.get()) {
                case "error" -> {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                case "garbage" -> body = "this is not json";
                case "slow" -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    body = "{}";
                }
                default -> body = """
                        {"choices":[{"message":{"content":"{\\"action\\":\\"rest\\",\\"args\\":{}}"}}]}
                        """;
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try {
            LlmClient.LlmResult ok = await(helper, LlmClient.requestRaw(endpoint, "test-model", 5, 64, 0.3f, "", "sys", "user"));
            helper.assertTrue(ok.usable(), "valid reply should be usable: " + ok.failure());
            helper.assertTrue(LlmActions.validate(ok.json()) != null, "reply should validate as an action: " + ok.json());
            helper.assertTrue(!lastRequestBody.get().contains("response_format"),
                    "request must not send response_format (LM Studio rejects json_object): " + lastRequestBody.get());

            mode.set("error");
            LlmClient.LlmResult httpError = await(helper, LlmClient.requestRaw(endpoint, "test-model", 5, 64, 0.3f, "", "sys", "user"));
            helper.assertTrue(!httpError.usable() && httpError.failure() != null,
                    "500 must collapse to a failure outcome");

            mode.set("garbage");
            LlmClient.LlmResult garbage = await(helper, LlmClient.requestRaw(endpoint, "test-model", 5, 64, 0.3f, "", "sys", "user"));
            helper.assertTrue(!garbage.usable(), "malformed body must collapse to a failure outcome");

            mode.set("slow");
            long start = System.nanoTime();
            LlmClient.LlmResult timeout = await(helper, LlmClient.requestRaw(endpoint, "test-model", 1, 64, 0.3f, "", "sys", "user"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            helper.assertTrue(!timeout.usable(), "slow server must time out into a failure outcome");
            helper.assertTrue(elapsedMs < 4_000, "timeout case took too long: " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void llmContentParsing(GameTestHelper helper) {
        var fenced = LlmActions.parseJsonObject("```json\n{\"action\":\"rest\",\"args\":{}}\n```");
        helper.assertTrue(fenced != null && "rest".equals(fenced.get("action").getAsString()),
                "code-fenced JSON must parse: " + fenced);
        helper.assertTrue(LlmActions.parseJsonObject("Sure! {\"action\":\"rest\",\"args\":{}} — done.") != null,
                "JSON embedded in prose must parse");
        helper.assertTrue(LlmActions.parseJsonObject("not json at all") == null, "garbage must not parse");
        helper.assertTrue(LlmActions.parseJsonObject("") == null, "empty content must not parse");
        helper.assertTrue(LlmActions.parseJsonObject("[]") == null, "non-object JSON must not parse");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void llmPromptListsOpenTaskKeys(GameTestHelper helper) {
        BoardTask task = new BoardTask(UUID.randomUUID().toString(), "comfort:light:" + UUID.randomUUID(),
                BoardTask.Type.COMFORT_LIGHT, BoardTask.Status.OPEN, null, 0, 0, 0, "3 dark spots near Dev", null);
        String prompt = LlmPrompts.userPrompt("[SELF] test", List.of(task), List.of(), List.of());
        helper.assertTrue(prompt.contains("comfort:light:"), "prompt must list the open-task key: " + prompt);
        helper.assertTrue(prompt.contains("claim"), "prompt must explain how to claim: " + prompt);
        helper.assertTrue(!prompt.contains("ACTIONS: move"), "prompt must not dump the raw action space: " + prompt);
        helper.succeed();
    }

    // --- end-to-end loop runs -----------------------------------------------------------

    @GameTest(template = "empty", batch = "agentsLoopLight", timeoutTicks = 300)
    public static void lightTaskEndToEnd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(6000);
        ServerPlayer player = placeMockPlayer(helper, new BlockPos(0, 1, 1));
        String key = "comfort:light:" + player.getUUID();
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(0, 1, 0));
        agent.getInventory().setItem(0, new ItemStack(Items.TORCH, 64));
        agent.goalSelector.getAvailableGoals().clear(); // deterministic loop tests: no random stroll
        // The detector scans a radius-8 cube around the player. The roof encloses that volume
        // from above (no sky cells), and carpet over the outer floor makes it non-rigid
        // (air-over-carpet is not spawnable), leaving only the small bare-floor room as
        // lit-able deficit — all of it within the agent's arrival distance, so the test
        // exercises the executor without depending on long-distance pathfinding.
        fillBox(helper, new BlockPos(-8, 0, -8), new BlockPos(8, 0, 8), Blocks.STONE, false);
        fillBox(helper, new BlockPos(-8, 7, -8), new BlockPos(8, 7, 8), Blocks.STONE, false);
        fillBox(helper, new BlockPos(-8, 1, -8), new BlockPos(8, 1, 8), Blocks.WHITE_CARPET, false);
        fillBox(helper, new BlockPos(-1, 1, -1), new BlockPos(1, 2, 1), Blocks.STONE, true);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
            }
        }

        boolean[] armed = {false};
        boolean[] finished = {false};
        int[] pollTicks = {0};
        // Registered at body time: GameTestHelper.onEachTick pre-fills the runAtTickTime map,
        // which must never be mutated from inside a delayed callback (fastutil NPE).
        helper.onEachTick(() -> {
            try {
                if (!armed[0] || finished[0]) {
                    return;
                }
                pollTicks[0]++;
                if (pollTicks[0] == 240) {
                    long torches = BlockPos.betweenClosedStream(helper.absolutePos(new BlockPos(-2, 0, -2)),
                                    helper.absolutePos(new BlockPos(2, 4, 2)))
                            .filter(pos -> level.getBlockState(pos).is(Blocks.TORCH)).count();
                    StringBuilder cells = new StringBuilder();
                    BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
                    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                    int scanRadius = Config.AGENTS_LIGHT_SCAN_RADIUS.get();
                    for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                                cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                                if (!level.getBlockState(cursor).isAir()) {
                                    continue;
                                }
                                if (!net.minecraft.world.level.block.Block.canSupportRigidBlock(
                                        level, cursor.move(net.minecraft.core.Direction.DOWN))) {
                                    cursor.move(net.minecraft.core.Direction.UP);
                                    continue;
                                }
                                cursor.move(net.minecraft.core.Direction.UP);
                                if (!level.canSeeSky(cursor)
                                        && level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, cursor)
                                                < Config.AGENTS_LIGHT_THRESHOLD.get()) {
                                    cells.append('(').append(dx).append(',').append(dy).append(',').append(dz)
                                            .append(")floor=").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                                    .getKey(level.getBlockState(cursor.below()).getBlock()).getPath())
                                            .append(";light=").append(level.getBrightness(
                                                    net.minecraft.world.level.LightLayer.BLOCK, cursor)).append(' ');
                                }
                            }
                        }
                    }
                    helper.assertTrue(false, "light task stalled at poll tick " + pollTicks[0]
                            + " (task=" + boardTaskByKey(level, key) + ", label=" + agent.currentTask()
                            + ", torchesNearBox=" + torches + ", agentPos=" + agent.blockPosition()
                            + ", speed=" + agent.getAttributeValue(
                                    net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                            + ", navDone=" + agent.getNavigation().isDone()
                            + ", hasPath=" + (agent.getNavigation().getPath() != null)
                            + ", stuck=" + agent.getNavigation().getPath() + ")"
                            + " remainingSpots=" + cells);
                }
                AgentDecisionLoop.runOnce(level, key::equals);
                BoardTask current = boardTaskByKey(level, key);
                if (current == null) {
                    return;
                }
                if (current.status() == BoardTask.Status.CLAIMED) {
                    helper.assertTrue(agent.getUUID().equals(current.claimantUuid()),
                            "our agent should hold the light claim");
                    helper.assertTrue(key.equals(agent.currentTask()),
                            "currentTask() should show the claimed key");
                }
                if (current.status() == BoardTask.Status.DONE) {
                    finished[0] = true;
                    helper.assertTrue(agent.getUUID().equals(current.claimantUuid()),
                            "loop should claim the light task before auto-complete:\n"
                                    + AgentWorldData.board(level).tasks());
                    helper.assertTrue(AgentWorldData.pool(level).entries().stream()
                                    .anyMatch(e -> e.outcome() == PoolEntry.Outcome.SUCCESS),
                            "auto-complete should write a pool success entry");
                    helper.assertTrue(!key.equals(agent.currentTask()),
                            "label should move on after resolution: " + agent.currentTask());
                    level.getServer().getPlayerList().remove(player);
                    helper.succeed();
                }
            } catch (Throwable t) {
                level.getServer().getPlayerList().remove(player);
                throw t;
            }
        });

        helper.runAfterDelay(40, () -> {
            ComfortDetectors.runOnce(level); // raises the deficit (idempotent with the real tick)
            helper.assertTrue(AgentWorldData.board(level).activeTask(key) != null,
                    "deficit should be raised before the loop runs:\n"
                            + AgentWorldData.board(level).tasks());
            armed[0] = true;
        });
    }

    @GameTest(template = "empty", batch = "agentsLoopBuild", timeoutTicks = 300)
    public static void buildTaskPlacesBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Blueprint pillar = new Blueprint("testpillar", new Vec3i(1, 2, 1), List.of(
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 0, 0)),
                new Blueprint.Placement("minecraft:stone", new BlockPos(0, 1, 0))));
        BlueprintLibrary.INSTANCE.registerForTest(pillar);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 1, 4));
        WorkshopAgentEntity agent = spawnAgent(helper, new BlockPos(3, 1, 4));
        agent.getInventory().setItem(0, new ItemStack(Items.STONE, 8));
        agent.goalSelector.getAvailableGoals().clear(); // deterministic loop tests: no random stroll

        BoardTask task = BuildQueue.enqueue(level, "testpillar", anchor);
        helper.assertTrue(task != null && task.status() == BoardTask.Status.OPEN, "build task should be raised");

        boolean[] finished = {false};
        // Registered at body time: GameTestHelper.onEachTick pre-fills the runAtTickTime map,
        // which must never be mutated from inside a delayed callback (fastutil NPE).
        helper.onEachTick(() -> {
            try {
                if (finished[0]) {
                    return;
                }
                AgentDecisionLoop.runOnce(level, task.key()::equals);
                BoardTask current = boardTaskByKey(level, task.key());
                if (current != null && current.status() == BoardTask.Status.DONE) {
                    finished[0] = true;
                    helper.assertTrue(agent.getUUID().equals(current.claimantUuid()),
                            "loop should claim the build task:\n" + AgentWorldData.board(level).tasks());
                    helper.assertTrue(level.getBlockState(anchor).is(Blocks.STONE)
                                    && level.getBlockState(anchor.above()).is(Blocks.STONE),
                            "both pillar blocks should be placed");
                    helper.assertTrue(AgentWorldData.pool(level).entries().stream()
                                    .anyMatch(e -> e.outcome() == PoolEntry.Outcome.SUCCESS),
                            "build completion should write a pool success entry");
                    helper.succeed();
                }
            } catch (Throwable t) {
                throw t;
            }
        });
    }

    @GameTest(template = "empty")
    public static void boardReleaseUnclaims(GameTestHelper helper) {
        TaskBoard board = new TaskBoard();
        AgentDecisionPool pool = new AgentDecisionPool();
        long now = 1000;
        String key = "comfort:light:" + UUID.randomUUID();
        BoardTask task = board.raiseIfAbsent(key, BoardTask.Type.COMFORT_LIGHT, "dark", now);
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();

        helper.assertTrue(board.claim(task.id(), agentA, 100, now), "initial claim");
        helper.assertTrue(!board.release(task.id(), agentB, now + 10), "non-claimant must not release");
        helper.assertTrue(board.release(task.id(), agentA, now + 10), "claimant releases");
        BoardTask released = board.tasks().get(0);
        helper.assertTrue(released.status() == BoardTask.Status.OPEN && released.claimantUuid() == null
                        && released.leaseUntilGameTime() == 0, "release clears claimant + lease: " + released);

        helper.assertTrue(board.claim(task.id(), agentB, 100, now + 20), "released task is claimable again");
        BoardTask reClaimed = board.tasks().get(0);
        helper.assertTrue(agentB.equals(reClaimed.claimantUuid()), "new claimant holds the task");
        helper.assertTrue(pool.size() == 0, "release must not write a pool entry");

        long leaseBefore = reClaimed.leaseUntilGameTime();
        helper.assertTrue(board.renewLease(task.id(), agentB, 300, now + 30), "claimant renews lease");
        helper.assertTrue(board.tasks().get(0).leaseUntilGameTime() == now + 30 + 300
                        && board.tasks().get(0).leaseUntilGameTime() > leaseBefore,
                "renewed lease should run from now: " + board.tasks().get(0));
        helper.assertTrue(!board.renewLease(task.id(), agentA, 300, now + 40), "non-claimant must not renew");
        helper.succeed();
    }

    // --- helpers ----------------------------------------------------------------

    private static LlmClient.LlmResult await(GameTestHelper helper,
            java.util.concurrent.CompletableFuture<LlmClient.LlmResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            helper.fail("LLM client future did not complete: " + e);
            return LlmClient.LlmResult.failed("test_hang"); // unreachable: fail() throws
        }
    }

    private static CompoundTag paletteEntry(String blockId) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", blockId);
        return entry;
    }

    private static CompoundTag structureBlock(BlockPos pos, int state) {
        CompoundTag block = new CompoundTag();
        block.putIntArray("pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
        block.putInt("state", state);
        return block;
    }

    private static Path writeTempSchematic(GameTestHelper helper, String name, String extension, CompoundTag root)
            throws IOException {
        Path dir = Files.createDirectories(Files.createTempDirectory("siasworkshop-gametest")
                .resolve("schematics-" + name));
        Path file = dir.resolve(name + extension);
        NbtIo.writeCompressed(root, file);
        return file;
    }

    private static BoardTask boardTaskByKey(ServerLevel level, String key) {
        for (BoardTask task : AgentWorldData.board(level).tasks()) {
            if (task.key().equals(key)) {
                return task;
            }
        }
        return null;
    }

    private static ServerPlayer placeMockPlayer(GameTestHelper helper, BlockPos relative) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos absolute = helper.absolutePos(relative);
        player.moveTo(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5, 0.0F, 0.0F);
        return player;
    }

    private static WorkshopAgentEntity spawnAgent(GameTestHelper helper, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        WorkshopAgentEntity agent = new WorkshopAgentEntity(SiasWorkshopEntities.WORKSHOP_AGENT.get(), level);
        BlockPos absolute = helper.absolutePos(relative);
        agent.moveTo(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(agent);
        helper.assertFalse(agent.isRemoved(), "agent failed to spawn");
        return agent;
    }

    private static void fillBox(GameTestHelper helper, BlockPos minRelative, BlockPos maxRelative,
            net.minecraft.world.level.block.Block block, boolean hollow) {
        for (int x = minRelative.getX(); x <= maxRelative.getX(); x++) {
            for (int y = minRelative.getY(); y <= maxRelative.getY(); y++) {
                for (int z = minRelative.getZ(); z <= maxRelative.getZ(); z++) {
                    if (hollow && x > minRelative.getX() && x < maxRelative.getX()
                            && y > minRelative.getY() && y < maxRelative.getY()
                            && z > minRelative.getZ() && z < maxRelative.getZ()) {
                        continue;
                    }
                    helper.setBlock(new BlockPos(x, y, z), block);
                }
            }
        }
    }
}
