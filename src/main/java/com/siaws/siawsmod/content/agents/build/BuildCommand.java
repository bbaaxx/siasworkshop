package com.siaws.siawsmod.content.agents.build;

import java.util.Comparator;
import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.siaws.siawsmod.content.agents.AgentWorldData;
import com.siaws.siawsmod.content.agents.WorkshopAgentEntity;
import com.siaws.siawsmod.content.agents.tasks.BoardTask;
import com.siaws.siawsmod.content.agents.tasks.TaskBoard;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /siasworkshop} agent commands (permission level 2, vanilla gamemode-command level):
 * {@code build <blueprint> [<x> <y> <z>]} enqueues a build task (anchor defaults to the executing
 * player's position; a village-foundation GUI is deliberately deferred), {@code list} prints the
 * loaded blueprints, {@code board} dumps the shared task board, {@code give <item> [<count>]}
 * stocks the nearest workshop agent within 8 blocks — its only other inventory source is
 * withdrawing build materials from nearby chests.
 */
public final class BuildCommand {
    private static final double GIVE_RADIUS = 8.0;

    private BuildCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("siasworkshop")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("build")
                                .then(Commands.argument("blueprint", StringArgumentType.word())
                                        .executes(BuildCommand::enqueueAtPlayer)
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(BuildCommand::enqueueAtPos))))
                        .then(Commands.literal("list").executes(BuildCommand::listBlueprints))
                        .then(Commands.literal("board").executes(BuildCommand::listBoard))
                        .then(Commands.literal("give")
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .executes(BuildCommand::giveOneStack)
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 640))
                                                .executes(BuildCommand::giveAmount)))));
    }

    private static int enqueueAtPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos anchor = BlockPos.containing(context.getSource().getPosition());
        return enqueue(context, anchor);
    }

    private static int enqueueAtPos(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos anchor = BlockPosArgument.getBlockPos(context, "pos");
        return enqueue(context, anchor);
    }

    private static int enqueue(CommandContext<CommandSourceStack> context, BlockPos anchor) {
        CommandSourceStack source = context.getSource();
        String blueprintName = StringArgumentType.getString(context, "blueprint");
        ServerLevel level = source.getLevel();
        BoardTask task = BuildQueue.enqueue(level, blueprintName, anchor);
        if (task == null) {
            source.sendFailure(Component.literal("Unknown blueprint '" + blueprintName + "' (import .schem/.nbt files into "
                    + "config/siasworkshop/blueprints/ and restart; loaded: " + BlueprintLibrary.INSTANCE.size() + ")"));
            return 0;
        }
        source.sendSystemMessage(Component.literal("Build task " + task.status() + ": " + task.key()));
        return 1;
    }

    private static int listBlueprints(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> names = BlueprintLibrary.INSTANCE.all().stream()
                .map(Blueprint::name).sorted(Comparator.naturalOrder()).toList();
        if (names.isEmpty()) {
            source.sendSystemMessage(Component.literal("No blueprints loaded — drop .schem/.nbt files into "
                    + "config/siasworkshop/blueprints/ and restart."));
        } else {
            source.sendSystemMessage(Component.literal("Blueprints (" + names.size() + "): " + String.join(", ", names)));
        }
        return names.size();
    }

    private static int listBoard(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<BoardTask> tasks = AgentWorldData.board(source.getLevel()).tasks();
        if (tasks.isEmpty()) {
            source.sendSystemMessage(Component.literal("Task board is empty."));
            return 0;
        }
        source.sendSystemMessage(Component.literal("Task board (" + tasks.size() + "):"));
        for (BoardTask task : tasks) {
            String claimant = task.claimantUuid() == null ? "-"
                    : task.claimantUuid().toString().substring(0, 8);
            String line = "  " + task.status() + " " + task.type() + " " + task.key()
                    + " [" + claimant + "]"
                    + (task.failureReason() == null ? "" : " (" + task.failureReason() + ")");
            source.sendSystemMessage(Component.literal(line));
        }
        return tasks.size();
    }

    private static int giveOneStack(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return give(context, 64);
    }

    private static int giveAmount(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return give(context, IntegerArgumentType.getInteger(context, "count"));
    }

    private static int give(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("Only entities can use this command."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        List<WorkshopAgentEntity> agents = level.getEntitiesOfClass(WorkshopAgentEntity.class,
                new AABB(origin).inflate(GIVE_RADIUS));
        if (agents.isEmpty()) {
            source.sendFailure(Component.literal("No workshop agent within " + (int) GIVE_RADIUS
                    + " blocks — summon one with /summon siasworkshop:workshop_agent"));
            return 0;
        }
        agents.sort(Comparator.comparingDouble(agent -> agent.distanceToSqr(source.getPosition())));
        WorkshopAgentEntity agent = agents.get(0);
        ItemStack stack = ItemArgument.getItem(context, "item").createItemStack(count, false);
        ItemStack remainder = agent.getInventory().addItem(stack);
        int delivered = count - remainder.getCount();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (delivered == 0) {
            source.sendFailure(Component.literal("Agent inventory is full."));
            return 0;
        }
        source.sendSystemMessage(Component.literal("Gave " + delivered + " × " + itemId + " to agent "
                + agent.getUUID().toString().substring(0, 8)));
        return delivered;
    }
}
