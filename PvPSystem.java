package com.savager_4;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PvPSystem implements ModInitializer {
    public static final String MOD_ID = "pvp-system";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int PVP_TICKS = 30 * 20;
    private static final int BED_COUNTDOWN_TICKS = 3 * 20;
    private static final int BED_COOLDOWN_TICKS = 5 * 60 * 20;

    private static final Map<UUID, Long> pvpUntil = new HashMap<>();
    private static final Map<UUID, BedRequest> bedRequests = new HashMap<>();
    private static final Map<UUID, Long> bedCooldownUntil = new HashMap<>();

    @Override
    public void onInitialize() {
        registerCommands();
        registerCombatEvents();
        registerServerTick();
        LOGGER.info("PvP System initialized.");
    }

    private static void registerCombatEvents() {
        // Server-authoritative combat tagging. A player and the player who attacked
        // them both receive a fresh 30-second PvP timer.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer victim)) {
                return true;
            }

            Entity attackerEntity = source.getEntity();
            if (attackerEntity instanceof ServerPlayer attacker && attacker != victim) {
                enterPvp(victim);
                enterPvp(attacker);
            }
            return true;
        });

        // Combat logging: leaving while tagged causes a normal lethal damage event,
        // which makes the player's inventory drop according to vanilla death rules.
        ServerPlayerEvents.LEAVE.register((handler, server) -> {
            ServerPlayer player = handler.player();
            if (isInPvp(player)) {
                LOGGER.info("{} logged out during PvP and was killed.", player.getGameProfile().name());
                player.hurtServer(player.level(), player.damageSources().generic(), Float.MAX_VALUE);
            }

            pvpUntil.remove(player.getUUID());
            bedRequests.remove(player.getUUID());
        });
    }

    private static void registerServerTick() {
        ServerTickEvents.END_SERVER_TICK.register(PvPSystem::tickServer);
    }

    private static void tickServer(MinecraftServer server) {
        long now = server.getTickCount();

        // Expire PvP timers and process /bed countdowns.
        pvpUntil.entrySet().removeIf(entry -> entry.getValue() <= now);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            BedRequest request = bedRequests.get(id);
            if (request == null) continue;

            // Any movement cancels the countdown.
            if (player.position().distanceToSqr(request.startPosition) > 0.0001D) {
                bedRequests.remove(id);
                player.sendSystemMessage(Component.literal("Bed teleport cancelled because you moved."));
                continue;
            }

            // Entering PvP cancels the countdown.
            if (isInPvp(player)) {
                bedRequests.remove(id);
                player.sendSystemMessage(Component.literal("Bed teleport cancelled because you entered PvP."));
                continue;
            }

            long elapsed = now - request.startTick;
            if (elapsed == 20 || elapsed == 40) {
                int remaining = 3 - (int) (elapsed / 20);
                player.sendSystemMessage(Component.literal(remaining + "..."));
            }

            if (elapsed >= BED_COUNTDOWN_TICKS) {
                bedRequests.remove(id);
                teleportToBed(player, now);
            }
        }
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("bed")
                    .executes(context -> executeBed(context.getSource()))
            );
        });
    }

    private static int executeBed(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can use /bed."));
            return 0;
        }

        long now = player.serverLevel().getServer().getTickCount();

        if (isInPvp(player)) {
            player.sendSystemMessage(Component.literal("You cannot use /bed while in PvP."));
            return 0;
        }

        Long cooldown = bedCooldownUntil.get(player.getUUID());
        if (cooldown != null && cooldown > now) {
            long seconds = Math.max(1, (cooldown - now + 19) / 20);
            player.sendSystemMessage(Component.literal("Bed recall is on cooldown for " + seconds + " more seconds."));
            return 0;
        }

        if (bedRequests.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Bed teleport is already counting down."));
            return 0;
        }

        if (!hasValidBedSpawn(player)) {
            player.sendSystemMessage(Component.literal("You do not have a valid bed spawn point."));
            return 0;
        }

        bedRequests.put(player.getUUID(), new BedRequest(player.position(), now));
        player.sendSystemMessage(Component.literal("Teleporting to your bed in 3..."));
        return 1;
    }

    private static boolean hasValidBedSpawn(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        if (config == null) return false;

        BlockPos pos = config.respawnData().pos();
        ServerLevel level = player.serverLevel().getServer().getLevel(config.respawnData().dimension());
        if (level == null) return false;

        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.BEDS);
    }

    private static void teleportToBed(ServerPlayer player, long now) {
        if (!hasValidBedSpawn(player)) {
            player.sendSystemMessage(Component.literal("Your bed spawn point is no longer valid."));
            return;
        }

        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        if (config == null) {
            player.sendSystemMessage(Component.literal("You do not have a valid bed spawn point."));
            return;
        }

        ServerLevel targetLevel = player.serverLevel().getServer().getLevel(config.respawnData().dimension());
        BlockPos pos = config.respawnData().pos();

        if (targetLevel == null || !targetLevel.getBlockState(pos).is(BlockTags.BEDS)) {
            player.sendSystemMessage(Component.literal("Your bed spawn point is no longer valid."));
            return;
        }

        boolean success = player.teleportTo(
            targetLevel,
            pos.getX() + 0.5D,
            pos.getY() + 1.0D,
            pos.getZ() + 0.5D,
            java.util.Set.of(),
            config.respawnData().yaw(),
            config.respawnData().pitch(),
            true
        );

        if (success) {
            bedCooldownUntil.put(player.getUUID(), now + BED_COOLDOWN_TICKS);
            player.sendSystemMessage(Component.literal("Teleported to your bed. Bed recall cooldown: 5 minutes."));
        } else {
            player.sendSystemMessage(Component.literal("Bed teleport failed."));
        }
    }

    public static void enterPvp(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            long until = serverPlayer.serverLevel().getServer().getTickCount() + PVP_TICKS;
            pvpUntil.put(serverPlayer.getUUID(), until);
        }
    }

    public static boolean isInPvp(ServerPlayer player) {
        Long until = pvpUntil.get(player.getUUID());
        return until != null && until > player.serverLevel().getServer().getTickCount();
    }

    private record BedRequest(Vec3 startPosition, long startTick) {}
}
