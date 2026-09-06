package com.example.autotpa;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TrapBuilder {
    private enum State {
        IDLE, WAITING_RTP, FIND_FLAT_LAND, WALK_TO_SPOT, EQUIP_PICKAXE,
        DIG_TO_BOTTOM, MINE_FLOOR_B2, PLACE_FLOOR_B2, STEP_ONTO_B2,
        MINE_FLOOR_B1, PLACE_FLOOR_B1, PLACE_ROOF, BUILD_WALLS, PLACE_COBWEBS, SET_HOME, WAIT_HOME_SEQUENCE
    }

    private static State currentState = State.IDLE;
    private static int delayTicks = 0;
    private static int airConfirmTicks = 0;
    public static boolean needsRebuildAfterShop = false;
    
    private static double rtpStartX = 0, rtpStartZ = 0;
    private static int rtpTimeoutTicks = 0;

    private static BlockPos b1;
    private static BlockPos b2;
    private static final List<BlockPos> blocksToDig = new ArrayList<>();
    private static final List<BlockPos> wallsToBuild = new ArrayList<>();
    
    public static void init(File configDir) {}

    public static void start(MinecraftClient client) {
        if (client.player == null) return;
        Autotpa.currentBotState = Autotpa.BotState.TRAP_BUILDING;
        CombatManager.releaseControls(client);
        
        int obsCount = countTotalItem(client, Items.OBSIDIAN);
        int webCount = countTotalItem(client, Items.COBWEB);
        boolean hasPick = hasAnyPickaxe(client);

        if (!hasPick || obsCount < 16 || webCount < 2) {
            Autotpa.sendFeedback("§e[Builder] Не хватает ресурсов (Кирка/Обсидиан/Паутина)! Закупка в /shop...");
            needsRebuildAfterShop = true;
            AutoSellManager.startAutoSell(client);
            return;
        }

        rtpStartX = client.player.getX();
        rtpStartZ = client.player.getZ();
        rtpTimeoutTicks = 300;
        delayTicks = 0;
        airConfirmTicks = 0;

        CommandQueue.send("rtp");
        currentState = State.WAITING_RTP;
        Autotpa.sendFeedback("§e[Builder] Отправлен /rtp. Ищу безопасную точку для постройки...");
    }

    public static void tick(MinecraftClient client) {
        if (currentState == State.IDLE || client.player == null || client.world == null) return;

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        switch (currentState) {
            case WAITING_RTP -> {
                double dx = client.player.getX() - rtpStartX;
                double dz = client.player.getZ() - rtpStartZ;
                double movedDist = Math.sqrt(dx * dx + dz * dz);

                if (movedDist > 50.0) {
                    Autotpa.sendFeedback("§a[Builder] Телепортация успешна! Проверяю местность...");
                    currentState = State.FIND_FLAT_LAND;
                    delayTicks = 30;
                    return;
                }

                if (rtpTimeoutTicks > 0) {
                    rtpTimeoutTicks--;
                } else {
                    Autotpa.sendFeedback("§e[Builder] Повторяю команду /rtp...");
                    rtpStartX = client.player.getX();
                    rtpStartZ = client.player.getZ();
                    rtpTimeoutTicks = 300;
                    CommandQueue.send("rtp");
                }
            }

            case FIND_FLAT_LAND -> {
                BlockPos p = client.player.getBlockPos();
                
                boolean inLiquid = client.world.getBlockState(p).isOf(Blocks.WATER) 
                        || client.world.getBlockState(p.down()).isOf(Blocks.WATER)
                        || client.world.getBlockState(p).isOf(Blocks.LAVA)
                        || client.world.getBlockState(p.down()).isOf(Blocks.LAVA);

                if (inLiquid) {
                    triggerNewRtp(client);
                    return;
                }

                BlockPos safeSpot = findFlatSolidSpot(client);
                if (safeSpot != null) {
                    setupBlueprint(client, safeSpot);
                    currentState = State.WALK_TO_SPOT;
                    delayTicks = 5;
                } else {
                    triggerNewRtp(client);
                }
            }

            case WALK_TO_SPOT -> {
                Vec3d target = Vec3d.ofBottomCenter(b1.up());
                double dx = target.x - client.player.getX();
                double dz = target.z - client.player.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist < 0.3) {
                    client.options.forwardKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);
                    Autotpa.sendFeedback("§a[Builder] Встал на точку. Достаю кирку...");
                    currentState = State.EQUIP_PICKAXE;
                    delayTicks = 5;
                } else {
                    float moveYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    client.player.setYaw(moveYaw);
                    client.player.setPitch(0);
                    client.options.forwardKey.setPressed(true);

                    if (client.player.horizontalCollision || target.y > client.player.getY() + 0.5) {
                        client.options.jumpKey.setPressed(true);
                    } else {
                        client.options.jumpKey.setPressed(false);
                    }
                }
            }

            case EQUIP_PICKAXE -> {
                int pickSlot = ensurePickaxeInHotbar(client);
                if (pickSlot != -1) {
                    client.player.getInventory().setSelectedSlot(pickSlot);
                    currentState = State.DIG_TO_BOTTOM;
                    airConfirmTicks = 0;
                } else {
                    Autotpa.sendFeedback("§c[Builder] Кирка не найдена! Иду в /shop...");
                    needsRebuildAfterShop = true;
                    AutoSellManager.startAutoSell(client);
                    reset();
                }
            }

            // 1. УМНОЕ КОПАНИЕ ЯМЫ (Без списков: проверяет с 1-го до 6-го блока каждый тик!)
            case DIG_TO_BOTTOM -> {
                BlockPos target = null;
                // Ищем первый несломанный блок строго сверху вниз
                for (BlockPos p : blocksToDig) {
                    if (!client.world.getBlockState(p).isAir()) {
                        target = p;
                        break;
                    }
                }

                if (target == null) {
                    // Все 6 блоков ямы стали воздухом! Ждем 5 тиков контрольной проверки.
                    airConfirmTicks++;
                    if (airConfirmTicks >= 5) {
                        BlockInteractionHelper.stopBreaking(client);
                        currentState = State.MINE_FLOOR_B2;
                        airConfirmTicks = 0;
                        delayTicks = 2;
                    }
                } else {
                    // Если блок вернулся из-за отката — цикл сам найдет его и начнет копать заново!
                    airConfirmTicks = 0;
                    BlockInteractionHelper.breakBlockLegit(client, target);
                }
            }

            case MINE_FLOOR_B2 -> {
                BlockPos floorB2 = b2.down(3);
                if (client.world.getBlockState(floorB2).isAir()) {
                    airConfirmTicks++;
                    if (airConfirmTicks >= 5) {
                        BlockInteractionHelper.stopBreaking(client);
                        currentState = State.PLACE_FLOOR_B2;
                        airConfirmTicks = 0;
                        delayTicks = 2;
                    }
                } else {
                    airConfirmTicks = 0;
                    BlockInteractionHelper.breakBlockLegit(client, floorB2);
                }
            }

            case PLACE_FLOOR_B2 -> {
                BlockPos floorB2 = b2.down(3);
                BlockState state = client.world.getBlockState(floorB2);

                if (state.isOf(Blocks.OBSIDIAN)) {
                    currentState = State.STEP_ONTO_B2;
                    delayTicks = 3;
                } else if (!state.isAir()) {
                    currentState = State.MINE_FLOOR_B2;
                    delayTicks = 2;
                } else {
                    BlockInteractionHelper.placeBlockLegit(client, floorB2, Items.OBSIDIAN);
                    delayTicks = 5;
                }
            }

            case STEP_ONTO_B2 -> {
                Vec3d target = Vec3d.ofBottomCenter(b2.down(2));
                double dx = target.x - client.player.getX();
                double dz = target.z - client.player.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist < 0.25) {
                    client.options.forwardKey.setPressed(false);
                    currentState = State.MINE_FLOOR_B1;
                    airConfirmTicks = 0;
                    delayTicks = 3;
                } else {
                    float moveYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    client.player.setYaw(moveYaw);
                    client.options.forwardKey.setPressed(true);
                }
            }

            case MINE_FLOOR_B1 -> {
                BlockPos floorB1 = b1.down(3);
                if (client.world.getBlockState(floorB1).isAir()) {
                    airConfirmTicks++;
                    if (airConfirmTicks >= 5) {
                        BlockInteractionHelper.stopBreaking(client);
                        currentState = State.PLACE_FLOOR_B1;
                        airConfirmTicks = 0;
                        delayTicks = 2;
                    }
                } else {
                    airConfirmTicks = 0;
                    BlockInteractionHelper.breakBlockLegit(client, floorB1);
                }
            }

            case PLACE_FLOOR_B1 -> {
                BlockPos floorB1 = b1.down(3);
                BlockState state = client.world.getBlockState(floorB1);

                if (state.isOf(Blocks.OBSIDIAN)) {
                    currentState = State.PLACE_ROOF;
                    delayTicks = 3;
                } else if (!state.isAir()) {
                    currentState = State.MINE_FLOOR_B1;
                    delayTicks = 2;
                } else {
                    BlockInteractionHelper.placeBlockLegit(client, floorB1, Items.OBSIDIAN);
                    delayTicks = 5;
                }
            }

            case PLACE_ROOF -> {
                boolean b1Placed = client.world.getBlockState(b1.up()).isOf(Blocks.OBSIDIAN);
                boolean b2Placed = client.world.getBlockState(b2.up()).isOf(Blocks.OBSIDIAN);

                if (b1Placed && b2Placed) {
                    currentState = State.BUILD_WALLS;
                    delayTicks = 3;
                    return;
                }

                if (!b1Placed) {
                    BlockInteractionHelper.placeBlockLegit(client, b1.up(), Items.OBSIDIAN);
                    delayTicks = 5;
                    return;
                }

                if (!b2Placed) {
                    BlockInteractionHelper.placeBlockLegit(client, b2.up(), Items.OBSIDIAN);
                    delayTicks = 5;
                }
            }

            // 8. УМНАЯ ПОСТРОЙКА СТЕН (Без списков: проверяет по кругу, пока все не станут обсидианом!)
            case BUILD_WALLS -> {
                BlockPos targetWall = null;
                for (BlockPos wp : wallsToBuild) {
                    if (!client.world.getBlockState(wp).isOf(Blocks.OBSIDIAN)) {
                        targetWall = wp;
                        break;
                    }
                }

                if (targetWall == null) {
                    BlockInteractionHelper.stopBreaking(client);
                    currentState = State.PLACE_COBWEBS;
                    delayTicks = 4;
                    return;
                }

                BlockState state = client.world.getBlockState(targetWall);
                if (!state.isAir()) {
                    BlockInteractionHelper.breakBlockLegit(client, targetWall);
                } else {
                    BlockInteractionHelper.stopBreaking(client);
                    BlockInteractionHelper.placeBlockLegit(client, targetWall, Items.OBSIDIAN);
                    delayTicks = 5;
                }
            }

            case PLACE_COBWEBS -> {
                BlockPos web1 = b1.down(2);
                BlockPos web2 = b2.down(2);

                boolean web1Placed = client.world.getBlockState(web1).isOf(Blocks.COBWEB);
                boolean web2Placed = client.world.getBlockState(web2).isOf(Blocks.COBWEB);

                if (web1Placed && web2Placed) {
                    currentState = State.SET_HOME;
                    delayTicks = 5;
                    return;
                }

                if (!web1Placed) {
                    BlockInteractionHelper.placeBlockLegit(client, web1, Items.COBWEB);
                    delayTicks = 5;
                    return;
                }

                if (!web2Placed) {
                    BlockInteractionHelper.placeBlockLegit(client, web2, Items.COBWEB);
                    delayTicks = 5;
                }
            }

            case SET_HOME -> {
                CombatManager.saveTrap(client);
                HomeSequence.startSequence(client);
                currentState = State.WAIT_HOME_SEQUENCE;
                delayTicks = 10;
            }

            case WAIT_HOME_SEQUENCE -> {
                if (HomeSequence.isIdle()) {
                    currentState = State.IDLE;
                    Autotpa.currentBotState = Autotpa.BotState.IDLE_TRAPPING;
                    Autotpa.sendFeedback("§a[Builder] Ловушка успешно построена и проверена! Дом 1 установлен.");
                } else {
                    delayTicks = 5;
                }
            }
        }
    }

    private static int ensurePickaxeInHotbar(MinecraftClient client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            String name = client.player.getInventory().getStack(i).getItem().toString().toLowerCase();
            if (name.contains("pickaxe")) return i;
        }
        for (int i = 9; i <= 35; i++) {
            String name = client.player.getInventory().getStack(i).getItem().toString().toLowerCase();
            if (name.contains("pickaxe")) {
                int syncId = client.player.playerScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, i, 7, SlotActionType.SWAP, client.player);
                return 7;
            }
        }
        return -1;
    }

    private static boolean hasAnyPickaxe(MinecraftClient client) {
        if (client.player == null) return false;
        for (int i = 0; i <= 35; i++) {
            String name = client.player.getInventory().getStack(i).getItem().toString().toLowerCase();
            if (name.contains("pickaxe")) return true;
        }
        return false;
    }

    private static int countTotalItem(MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null) return 0;
        int total = 0;
        for (int i = 0; i <= 35; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    private static void triggerNewRtp(MinecraftClient client) {
        rtpStartX = client.player.getX();
        rtpStartZ = client.player.getZ();
        rtpTimeoutTicks = 300;
        CommandQueue.send("rtp");
        currentState = State.WAITING_RTP;
        delayTicks = 20;
    }

    private static BlockPos findFlatSolidSpot(MinecraftClient client) {
        BlockPos playerPos = client.player.getBlockPos();
        Direction facing = client.player.getHorizontalFacing();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos candidate1 = playerPos.add(x, -1, z);
                BlockPos candidate2 = candidate1.offset(facing);

                if (isTerrainSuitable(client, candidate1) && isTerrainSuitable(client, candidate2)) {
                    return candidate1;
                }
            }
        }
        return null;
    }

    private static boolean isTerrainSuitable(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;

        for (int dy = 1; dy <= 3; dy++) {
            if (!client.world.getBlockState(pos.up(dy)).isAir()) return false;
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 5; dy++) {
                    BlockPos check = pos.add(dx, -dy, dz);
                    BlockState bs = client.world.getBlockState(check);
                    Block b = bs.getBlock();

                    if (b instanceof FallingBlock 
                            || bs.isOf(Blocks.SAND) 
                            || bs.isOf(Blocks.RED_SAND) 
                            || bs.isOf(Blocks.GRAVEL)
                            || bs.isOf(Blocks.SUSPICIOUS_SAND)
                            || bs.isOf(Blocks.SUSPICIOUS_GRAVEL)
                            || bs.isOf(Blocks.SANDSTONE)
                            || bs.isOf(Blocks.RED_SANDSTONE)) {
                        return false;
                    }

                    if (bs.isOf(Blocks.WATER) || bs.isOf(Blocks.LAVA)) {
                        return false;
                    }

                    if (bs.isAir() || bs.isReplaceable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void setupBlueprint(MinecraftClient client, BlockPos spot) {
        b1 = spot;
        Direction f = client.player.getHorizontalFacing();
        b2 = b1.offset(f);

        blocksToDig.clear();
        wallsToBuild.clear();

        for (int depth = 0; depth < 3; depth++) {
            blocksToDig.add(b1.down(depth));
            blocksToDig.add(b2.down(depth));
        }

        for (int dy = 0; dy <= 2; dy++) {
            addWallIfUnique(b1.down(dy).north());
            addWallIfUnique(b1.down(dy).south());
            addWallIfUnique(b1.down(dy).east());
            addWallIfUnique(b1.down(dy).west());
            addWallIfUnique(b2.down(dy).north());
            addWallIfUnique(b2.down(dy).south());
            addWallIfUnique(b2.down(dy).east());
            addWallIfUnique(b2.down(dy).west());
        }
    }

    private static void addWallIfUnique(BlockPos p) {
        if (!wallsToBuild.contains(p) && !isCenterBlock(p)) {
            wallsToBuild.add(p);
        }
    }

    private static boolean isCenterBlock(BlockPos p) {
        for (int i = 0; i < 4; i++) {
            if (p.equals(b1.down(i)) || p.equals(b2.down(i))) return true;
        }
        return false;
    }

    public static void reset() {
        currentState = State.IDLE;
        BlockInteractionHelper.stopBreaking(MinecraftClient.getInstance());
        blocksToDig.clear();
        wallsToBuild.clear();
        airConfirmTicks = 0;
    }
}