package com.savager_4.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

public class PvPSystemClient implements ClientModInitializer {
    private static final int PVP_TICKS = 30 * 20;
    private static final double MAX_ASSIST_DISTANCE = 6.0D;
    private static final double MAX_ASSIST_ANGLE = 35.0D;
    private static final float ASSIST_STRENGTH = 0.50F;

    private static int pvpTicksRemaining = 0;
    private static int previousHurtTime = 0;

    @Override
    public void onInitializeClient() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof LocalPlayer && entity instanceof Player && entity != player) {
                pvpTicksRemaining = PVP_TICKS;
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(PvPSystemClient::tick);
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            pvpTicksRemaining = 0;
            previousHurtTime = 0;
            return;
        }

        if (player.hurtTime > previousHurtTime) {
            pvpTicksRemaining = PVP_TICKS;
        }
        previousHurtTime = player.hurtTime;

        if (pvpTicksRemaining > 0) {
            pvpTicksRemaining--;
            applyGentleAimAssist(client, player);
        }
    }

    private static void applyGentleAimAssist(Minecraft client, LocalPlayer player) {
        if (client.screen != null || player.isSpectator()) return;

        Player target = findBestTarget(player);
        if (target == null) return;

        Vec3 eye = player.getEyePosition();
        Vec3 center = target.getBoundingBox().getCenter();

        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001D) return;

        float desiredYaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredPitch = (float) -(Math.atan2(dy, horizontal) * (180.0D / Math.PI));

        float yawDifference = Mth.wrapDegrees(desiredYaw - player.getYRot());
        float pitchDifference = desiredPitch - player.getXRot();

        // Only assist when the target is already reasonably close to the crosshair.
        if (Math.abs(yawDifference) > MAX_ASSIST_ANGLE || Math.abs(pitchDifference) > MAX_ASSIST_ANGLE) {
            return;
        }

        // 50% of the remaining angular difference per tick, producing a gentle
        // correction rather than an instant snap.
        player.setYRot(player.getYRot() + yawDifference * ASSIST_STRENGTH);
        player.setXRot(Mth.clamp(player.getXRot() + pitchDifference * ASSIST_STRENGTH, -90.0F, 90.0F));
        player.setYHeadRot(player.getYRot());
    }

    private static Player findBestTarget(LocalPlayer player) {
        Level level = player.level();
        Player best = null;
        double bestScore = Double.MAX_VALUE;

        for (Player candidate : level.players()) {
            if (candidate == player || !candidate.isAlive() || candidate.isSpectator()) continue;

            double distance = player.distanceTo(candidate);
            if (distance > MAX_ASSIST_DISTANCE) continue;

            Vec3 eye = player.getEyePosition();
            Vec3 center = candidate.getBoundingBox().getCenter();
            double dx = center.x - eye.x;
            double dy = center.y - eye.y;
            double dz = center.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);

            float desiredYaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            float desiredPitch = (float) -(Math.atan2(dy, horizontal) * (180.0D / Math.PI));

            double yawDiff = Math.abs(Mth.wrapDegrees(desiredYaw - player.getYRot()));
            double pitchDiff = Math.abs(desiredPitch - player.getXRot());
            double angle = yawDiff + pitchDiff;

            if (angle <= MAX_ASSIST_ANGLE * 2.0D) {
                double score = angle + distance * 0.5D;
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }

        return best;
    }
}
