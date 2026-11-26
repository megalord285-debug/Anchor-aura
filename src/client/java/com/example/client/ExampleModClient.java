package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ExampleModClient implements ClientModInitializer {

    static boolean aimEnabled = false;
    static boolean fastPlaceEnabled = false;
    static boolean anchorEnabled = false;
    static KeyMapping aimKey;
    static KeyMapping fastPlaceKey;
    static KeyMapping anchorKey;

    // انکرهایی که خودم گذاشتم
    static final List<BlockPos> myAnchors = new ArrayList<>();
    static final Map<BlockPos, Integer> pending = new HashMap<>();
    static int anchorCooldown = 0;
    static int restoreSlot = -1;

    static final double AIM_RANGE = 3.2;
    static final float SMOOTH = 0.6f;
    static final float MAX_STEP = 45f;

    static final double REACH = 4.5;
    static final int ANCHOR_DELAY = 1;   // تأخیر بین کارها (تیک)

    @Override
    public void onInitializeClient() {
        KeyMapping.Category cat = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("aimassist", "main"));

        aimKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.aimassist.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));
        fastPlaceKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fastplace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, cat));
        anchorKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.anchoraura.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, cat));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (aimKey.consumeClick()) {
                aimEnabled = !aimEnabled;
                msg(client, "AimAssist: " + (aimEnabled ? "ON" : "OFF"));
            }
            while (fastPlaceKey.consumeClick()) {
                fastPlaceEnabled = !fastPlaceEnabled;
                msg(client, "FastPlace: " + (fastPlaceEnabled ? "ON" : "OFF"));
            }
            while (anchorKey.consumeClick()) {
                anchorEnabled = !anchorEnabled;
                myAnchors.clear();
                pending.clear();
                msg(client, "AnchorAura: " + (anchorEnabled ? "ON" : "OFF"));
            }

            if (client.player == null || client.level == null
                    || client.screen != null || client.gameMode == null) return;

            LocalPlayer me = client.player;

            if (aimEnabled) {
                Player t = nearest(client, me, AIM_RANGE);
                if (t != null) aim(me, t);
            }

            if (fastPlaceEnabled && client.options.keyUse.isDown()
                    && me.getMainHandItem().getItem() instanceof BlockItem
                    && client.hitResult != null
                    && client.hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult hit = (BlockHitResult) client.hitResult;
                client.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, hit);
                me.swing(InteractionHand.MAIN_HAND);
            }

            if (anchorEnabled) anchorTick(client, me);
        });
    }

    static void msg(Minecraft client, String text) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(text), true);
        }
    }

    static Player nearest(Minecraft client, LocalPlayer me, double range) {
        Player best = null;
        double bestDist = range;
        for (Player p : client.level.players()) {
            if (p == me || !p.isAlive() || p.isSpectator()) continue;
            double dist = me.distanceTo(p);
            if (dist <= bestDist) { bestDist = dist; best = p; }
        }
        return best;
    }

    static void aim(LocalPlayer me, Player target) {
        Vec3 aim = target.getBoundingBox().getCenter();
        Vec3 d = aim.subtract(me.getEyePosition());

        float tYaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float tPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));

        float dYaw = Mth.wrapDegrees(tYaw - me.getYRot());
        float dPitch = tPitch - me.getXRot();

        me.setYRot(me.getYRot() + Mth.clamp(dYaw * SMOOTH, -MAX_STEP, MAX_STEP));
        me.setXRot(Mth.clamp(
                me.getXRot() + Mth.clamp(dPitch * SMOOTH, -MAX_STEP, MAX_STEP),
                -90f, 90f));
    }

    static int findSlot(LocalPlayer me, boolean glowstone) {
        for (int i = 0; i < 9; i++) {
            boolean isGlow = me.getInventory().getItem(i).is(Items.GLOWSTONE);
            if (glowstone && isGlow) return i;
            if (!glowstone && !isGlow) return i;
        }
        return -1;
    }

    static void anchorTick(Minecraft client, LocalPlayer me) {
        // ۱) تشخیص انکری که الان دارم می‌ذارم
        if (client.options.keyUse.isDown()
                && me.getMainHandItem().is(Items.RESPAWN_ANCHOR)
                && client.hitResult != null
                && client.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult h = (BlockHitResult) client.hitResult;
            BlockPos target = client.level.getBlockState(h.getBlockPos()).canBeReplaced()
                    ? h.getBlockPos()
                    : h.getBlockPos().relative(h.getDirection());
            if (!myAnchors.contains(target)) pending.put(target, 10);
        }

        // ۲) اگه انکر واقعاً اومد، مال منه
        Iterator<Map.Entry<BlockPos, Integer>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> e = it.next();
            BlockPos p = e.getKey();
            if (client.level.getBlockState(p).is(Blocks.RESPAWN_ANCHOR)) {
                if (!myAnchors.contains(p)) myAnchors.add(p);
                it.remove();
            } else {
                e.setValue(e.getValue() - 1);
                if (e.getValue() <= 0) it.remove();
            }
        }

        if (anchorCooldown > 0) { anchorCooldown--; return; }

        // ۳) فقط روی انکرهای خودم: شارژ بعد انفجار
        for (BlockPos p : new ArrayList<>(myAnchors)) {
            BlockState s = client.level.getBlockState(p);
            if (!s.is(Blocks.RESPAWN_ANCHOR)) { myAnchors.remove(p); continue; }

            Vec3 center = Vec3.atCenterOf(p);
            if (me.getEyePosition().distanceTo(center) > REACH) continue;

            int charge = s.getValue(RespawnAnchorBlock.CHARGE);
            BlockHitResult hit = new BlockHitResult(center, Direction.UP, p, false);

            if (charge == 0) {
                int glow = findSlot(me, true);
                if (glow < 0) continue;
                restoreSlot = me.getInventory().getSelectedSlot();
                if (restoreSlot == glow) restoreSlot = findSlot(me, false);
                me.getInventory().setSelectedSlot(glow);
                client.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, hit);
                me.swing(InteractionHand.MAIN_HAND);
            } else {
                int slot = restoreSlot >= 0 ? restoreSlot : findSlot(me, false);
                if (slot < 0) continue;
                me.getInventory().setSelectedSlot(slot);
                client.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, hit);
                me.swing(InteractionHand.MAIN_HAND);
            }
            anchorCooldown = ANCHOR_DELAY;
            return;
        }
    }
				}
