package com.example.autotpa;

import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class BlockInteractionHelper {

    public static final int SLOT_SWORD = 0;
    public static final int SLOT_COBWEB = 1;
    public static final int SLOT_AXE = 2;
    public static final int SLOT_PEARL = 3;
    public static final int SLOT_GAPPLE = 4;
    public static final int SLOT_OBSIDIAN = 5;
    public static final int SLOT_BOW = 6;
    public static final int SLOT_PICKAXE = 7;
    public static final int SLOT_POTION = 8;

    private static BlockPos currentBreakingBlock = null;
    private static Block initialBreakingBlockType = null;
    private static Direction lockedBreakingSide = null;

    private static int breakingTicks = 0;
    private static int toolSwitchCooldown = 0;
    private static int postBreakTicks = 0;

    // СИСТЕМА УМНОЙ ПАУЗЫ ПРИ СБОЕ (BACKOFF)
    private static int breakRetryCooldown = 0;
    private static int consecutiveFailures = 0;

    public static boolean isBreaking() {
        return currentBreakingBlock != null || breakRetryCooldown > 0;
    }

    // =========================================================================
    // BARITONE-НАВОДКА: СМЕЩЕНИЕ ОТ ЦЕНТРА (0.30 - 0.70) + ОБХОД АНТИЧИТА
    // =========================================================================
    public static Vec3d getNaturalAimPoint(MinecraftClient client, BlockPos pos, boolean isCobweb) {
        // Уникальное смещение для каждого блока (никогда не 0.500 ровно)
        long hash = pos.asLong();
        double offX = 0.30 + (((hash & 0xFF) % 40) / 100.0);         // 0.30 - 0.70
        double offY = isCobweb ? (0.25 + ((((hash >> 4) & 0xFF) % 20) / 100.0))
                               : (0.35 + ((((hash >> 8) & 0xFF) % 30) / 100.0));
        double offZ = 0.30 + ((((hash >> 16) & 0xFF) % 40) / 100.0); // 0.30 - 0.70

        // Органическое микро-движение руки
        double driftX = Math.sin(breakingTicks * 0.15) * 0.02;
        double driftY = Math.cos(breakingTicks * 0.15) * 0.02;
        double driftZ = Math.sin((breakingTicks + 3) * 0.15) * 0.02;

        Vec3d eye = (client.player != null) ? client.player.getEyePos() : Vec3d.ofCenter(pos);
        Vec3d raw = new Vec3d(pos.getX() + offX + driftX, pos.getY() + offY + driftY, pos.getZ() + offZ + driftZ);

        // Если голова внутри паутины — выносим прицел чуть вперед, чтобы рейкаст шел сквозь блок
        if (eye.squaredDistanceTo(raw) < 0.75) {
            float yawRad = (float) Math.toRadians(client.player != null ? client.player.getYaw() : 0);
            double fwdX = -Math.sin(yawRad) * 0.30;
            double fwdZ = Math.cos(yawRad) * 0.30;
            return new Vec3d(pos.getX() + 0.5 + fwdX, pos.getY() + offY, pos.getZ() + 0.5 + fwdZ);
        }

        return raw;
    }

    public static boolean aimAtBlock(MinecraftClient client, BlockPos pos, boolean isCobweb) {
        if (client.player == null || client.options == null) return false;

        Vec3d eye = client.player.getEyePos();
        Vec3d target = getNaturalAimPoint(client, pos, isCobweb);

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (hDist > 0.005)
                ? (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0)
                : client.player.getYaw();

        float targetPitch = (float) MathHelper.wrapDegrees(-Math.toDegrees(Math.atan2(dy, hDist)));
        targetPitch = MathHelper.clamp(targetPitch, -89.5f, 89.5f);

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;

        float speed = Math.min(50.0f, Math.max(10.0f, (float) Math.hypot(deltaYaw, deltaPitch) * 0.7f));
        float stepYaw = MathHelper.clamp(deltaYaw, -speed, speed);
        float stepPitch = MathHelper.clamp(deltaPitch, -speed, speed);

        // Расчет шага сенсора мыши (GCD)
        double sensitivity = client.options.getMouseSensitivity().getValue();
        double f = sensitivity * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;

        if (gcd > 0.0001) {
            stepYaw = (float) (Math.round(stepYaw / gcd) * gcd);
            stepPitch = (float) (Math.round(stepPitch / gcd) * gcd);
        }

        client.player.setYaw(currentYaw + stepYaw);
        client.player.setPitch(MathHelper.clamp(currentPitch + stepPitch, -89.5f, 89.5f));

        return Math.abs(deltaPitch) < 25.0f && Math.abs(deltaYaw) < 25.0f;
    }

    // =========================================================================
    // БЕЗУПРЕЧНОЕ ЛОМАНИЕ С СИСТЕМОЙ ПРОГРЕССИВНОГО ПЕРЕРЫВА
    // =========================================================================
    public static boolean breakBlockLegit(MinecraftClient client, BlockPos pos) {
        if (client.world == null || client.player == null || client.interactionManager == null) {
            stopBreaking(client);
            return false;
        }

        // Защита от переключения между разными координатами паутины
        if (currentBreakingBlock != null && !currentBreakingBlock.equals(pos)) {
            BlockState activeState = client.world.getBlockState(currentBreakingBlock);
            if (activeState.isOf(Blocks.COBWEB) && client.world.getBlockState(pos).isOf(Blocks.COBWEB)
                    && currentBreakingBlock.isWithinDistance(client.player.getEyePos(), 3.5)) {
                pos = currentBreakingBlock;
            }
        }

        BlockState currentState = client.world.getBlockState(pos);

        // 1. Успех: блок сломан
        if (currentState.isAir() || (initialBreakingBlockType != null && !currentState.isOf(initialBreakingBlockType))) {
            postBreakTicks++;
            if (postBreakTicks < 2) return true;
            stopBreaking(client);
            consecutiveFailures = 0;
            breakRetryCooldown = 0;
            return false;
        }

        postBreakTicks = 0;
        if (currentState.getHardness(client.world, pos) < 0.0f) {
            stopBreaking(client);
            consecutiveFailures = 0;
            breakRetryCooldown = 0;
            return false;
        }

        boolean isCobweb = currentState.isOf(Blocks.COBWEB);

        // 2. СИСТЕМА ПАУЗЫ: если был сбой — выжидаем время и НЕ шлем пакеты
        if (breakRetryCooldown > 0) {
            breakRetryCooldown--;
            aimAtBlock(client, pos, isCobweb);
            if (client.options != null) {
                client.options.attackKey.setPressed(false);
            }
            return true;
        }

        // 3. Подготовка меча
        if (isCobweb) {
            if (!ensureSwordInHand(client)) {
                return true;
            }
        } else {
            selectOptimalTool(client, currentState);
            if (toolSwitchCooldown > 0) {
                toolSwitchCooldown--;
                return true;
            }
        }

        // 4. Поворот головы (не в центр, а под естественным углом)
        boolean onTarget = aimAtBlock(client, pos, isCobweb);

        // 5. Инициализация цели
        if (currentBreakingBlock == null || !currentBreakingBlock.equals(pos)) {
            stopBreaking(client);
            currentBreakingBlock = pos;
            initialBreakingBlockType = currentState.getBlock();
            breakingTicks = 0;
            postBreakTicks = 0;
            lockedBreakingSide = null;
        }

        // 6. Определение честной грани через рейкаст
        if (lockedBreakingSide == null) {
            Vec3d targetPoint = getNaturalAimPoint(client, pos, isCobweb);
            Vec3d eye = client.player.getEyePos();
            BlockHitResult bhr = client.world.raycast(new RaycastContext(
                    eye, targetPoint, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player
            ));

            if (bhr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(pos)) {
                lockedBreakingSide = bhr.getSide();
            } else {
                lockedBreakingSide = (eye.y >= pos.getY() + 0.5) ? Direction.UP : Direction.DOWN;
            }
        }

        // 7. РАСЧЕТ ВРЕМЕНИ И ОБНАРУЖЕНИЕ СБОЯ
        float delta = currentState.calcBlockBreakingDelta(client.player, client.world, pos);
        int expectedTicks = (delta > 0.0f) ? (int) Math.ceil(1.0f / delta) : (isCobweb ? 40 : 100);
        int maxAllowedTicks = expectedTicks + 25; // запас на пинг

        // ЕСЛИ СЛУЧИЛСЯ СБОЙ: делаем ощутимый перерыв перед повторной попыткой!
        if (breakingTicks > maxAllowedTicks) {
            stopBreaking(client);
            consecutiveFailures++;

            // 1-й сбой: пауза 15 тиков (0.75 сек)
            // 2-й сбой подряд: пауза 30 тиков (1.5 сек)
            // 3-й сбой: пауза 50 тиков (2.5 сек)
            if (consecutiveFailures == 1) {
                breakRetryCooldown = 15;
            } else if (consecutiveFailures == 2) {
                breakRetryCooldown = 30;
            } else {
                breakRetryCooldown = 50;
                consecutiveFailures = 0;
            }
            return true;
        }

        if (breakingTicks == 0 && !onTarget) {
            return true;
        }

        // 8. СИНХРОНИЗАЦИЯ С ДВИЖКОМ MINECRAFT (ЧЕСТНАЯ 1X СКОРОСТЬ)
        // Фиксируем прицел на блоке и зажимаем клавишу атаки
        Vec3d aimP = getNaturalAimPoint(client, pos, isCobweb);
        client.crosshairTarget = new BlockHitResult(aimP, lockedBreakingSide, pos, false);

        if (client.options != null) {
            client.options.attackKey.setPressed(true);
        }

        breakingTicks++;
        return true;
    }

    public static void stopBreaking(MinecraftClient client) {
        if (client != null) {
            if (client.options != null) {
                client.options.attackKey.setPressed(false);
            }
            if (client.interactionManager != null && currentBreakingBlock != null && client.world != null) {
                BlockState state = client.world.getBlockState(currentBreakingBlock);
                if (initialBreakingBlockType != null && state.isOf(initialBreakingBlockType)) {
                    client.interactionManager.cancelBlockBreaking();
                }
            }
        }
        currentBreakingBlock = null;
        initialBreakingBlockType = null;
        lockedBreakingSide = null;
        breakingTicks = 0;
        postBreakTicks = 0;
    }

    public static void cancelBreaking(MinecraftClient client) {
        stopBreaking(client);
        breakRetryCooldown = 0;
        consecutiveFailures = 0;
    }

    // =========================================================================
    // УСТАНОВКА БЛОКОВ
    // =========================================================================
    // =========================================================================
    // УСТАНОВКА БЛОКОВ (ПАУТИНА В НОГИ + ЭНДЕР-СУНДУКИ В СТЕНЫ 1x1, БЕЗ КРЫШИ)
    // =========================================================================
    public static boolean placeBlockLegit(MinecraftClient client, BlockPos targetPos, Item itemToPlace) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;

        if (client.options != null) {
            client.options.attackKey.setPressed(false);
        }

        // Стены (эндер-сундуки / обсидиан) берем в слот 5 (SLOT_OBSIDIAN), паутину — в слот 1 (SLOT_COBWEB)
        int targetHotbarSlot = (itemToPlace == Items.OBSIDIAN || itemToPlace == Items.ENDER_CHEST) ? SLOT_OBSIDIAN : SLOT_COBWEB;

        // Если нужного блока нет в хотбаре — достаем из рюкзака (слоты 9-35)
        if (!client.player.getInventory().getStack(targetHotbarSlot).isOf(itemToPlace)) {
            for (int i = 9; i <= 35; i++) {
                if (client.player.getInventory().getStack(i).isOf(itemToPlace)) {
                    int syncId = client.player.playerScreenHandler.syncId;
                    client.interactionManager.clickSlot(syncId, i, targetHotbarSlot, SlotActionType.SWAP, client.player);
                    break;
                }
            }
        }

        if (!client.player.getInventory().getStack(targetHotbarSlot).isOf(itemToPlace)) return false;

        Vec3d eye = client.player.getEyePos();
        BlockPos trapFeet = CombatManager.getTrapFeetPos(client);

        // 1. УСТАНОВКА ЕДИНСТВЕННОЙ ПАУТИНЫ В НОГИ (на верхнюю грань блока под ногами)
        if (trapFeet != null && targetPos.equals(trapFeet)) {
            aimAtBlock(client, targetPos, true);
            if (client.player.getPitch() < 65.0f) return false;

            selectSlot(client, targetHotbarSlot);
            Vec3d straightDown = new Vec3d(eye.x, targetPos.getY(), eye.z);
            BlockHitResult hitResult = new BlockHitResult(straightDown, Direction.UP, targetPos.down(), false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
            client.player.swingHand(Hand.MAIN_HAND);
            return true;
        }

        // 2. УСТАНОВКА СТЕН (ЭНДЕР-СУНДУКОВ) ИЛИ СУШКА ИСТОЧНИКОВ ЖИДКОСТИ
        // Ищем соседний твёрдый блок, на грань которого можно поставить блок
        BlockPos bestNeighbor = null;
        Direction bestSide = null;
        Vec3d bestHitVec = null;
        double bestDist = Double.MAX_VALUE;

        Direction[] checkDirs = { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

        for (Direction dir : checkDirs) {
            BlockPos neighbor = targetPos.offset(dir);
            BlockState nState = client.world.getBlockState(neighbor);

            if (nState.isAir() || nState.isOf(Blocks.COBWEB) || nState.isOf(Blocks.WATER) || nState.isOf(Blocks.LAVA)
                    || !nState.getFluidState().isEmpty() || !nState.isSolidBlock(client.world, neighbor)) {
                continue;
            }

            Direction side = dir.getOpposite();
            Vec3d hitP = Vec3d.ofCenter(neighbor).add(side.getOffsetX() * 0.45, side.getOffsetY() * 0.45, side.getOffsetZ() * 0.45);

            BlockHitResult ray = client.world.raycast(new RaycastContext(
                    eye, hitP, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player
            ));

            if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(neighbor)) {
                double d = eye.squaredDistanceTo(hitP);
                if (d < bestDist) {
                    bestDist = d;
                    bestNeighbor = neighbor;
                    bestSide = side;
                    bestHitVec = hitP;
                }
            }
        }

        // Резервный поиск, если рейкаст слегка скользит по краю
        if (bestNeighbor == null) {
            for (Direction dir : checkDirs) {
                BlockPos neighbor = targetPos.offset(dir);
                BlockState nState = client.world.getBlockState(neighbor);
                if (!nState.isAir() && !nState.isOf(Blocks.WATER) && !nState.isOf(Blocks.LAVA)
                        && nState.getFluidState().isEmpty() && nState.isSolidBlock(client.world, neighbor)) {
                    bestNeighbor = neighbor;
                    bestSide = dir.getOpposite();
                    bestHitVec = Vec3d.ofCenter(neighbor).add(bestSide.getOffsetX() * 0.45, bestSide.getOffsetY() * 0.45, bestSide.getOffsetZ() * 0.45);
                    break;
                }
            }
        }

        if (bestNeighbor == null || bestSide == null || bestHitVec == null) return false;

        // Поворот камеры на блок-опору (естественный аим)
        aimAtBlock(client, bestNeighbor, false);

        selectSlot(client, targetHotbarSlot);
        BlockHitResult hitResult = new BlockHitResult(bestHitVec, bestSide, bestNeighbor, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        client.player.swingHand(Hand.MAIN_HAND);

        return true;
    }
    // =========================================================================
    // УСТАНОВКА ПАУТИНЫ ПОВЕРХ ДРУГОЙ ПАУТИНЫ (ДЛЯ СУШКИ ЛАВЫ И ВОДЫ СВЕРХУ)
    // =========================================================================
    public static boolean placeCobwebOnTopOf(MinecraftClient client, BlockPos basePos) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;

        // Достаем паутину в слот 1 (SLOT_COBWEB), если ее там нет
        if (!client.player.getInventory().getStack(SLOT_COBWEB).isOf(Items.COBWEB)) {
            for (int i = 9; i <= 35; i++) {
                if (client.player.getInventory().getStack(i).isOf(Items.COBWEB)) {
                    int syncId = client.player.playerScreenHandler.syncId;
                    client.interactionManager.clickSlot(syncId, i, SLOT_COBWEB, SlotActionType.SWAP, client.player);
                    break;
                }
            }
        }
        if (!client.player.getInventory().getStack(SLOT_COBWEB).isOf(Items.COBWEB)) return false;

        selectSlot(client, SLOT_COBWEB);

        // Смотрим строго вниз на верхнюю грань базовой паутины
        Vec3d hitVec = new Vec3d(basePos.getX() + 0.5, basePos.getY() + 0.98, basePos.getZ() + 0.5);
        aimAtBlock(client, basePos, true);

        // Кликаем по верхней грани (Direction.UP)
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, basePos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }
    public static void selectOptimalTool(MinecraftClient client, BlockState state) {
        if (client.player == null) return;

        if (state.isOf(Blocks.COBWEB)) {
            ensureSwordInHand(client);
            return;
        }

        ItemStack axeStack = client.player.getInventory().getStack(SLOT_AXE);
        ItemStack pickaxeStack = client.player.getInventory().getStack(SLOT_PICKAXE);

        float axeSpeed = isAxeItem(axeStack) ? axeStack.getMiningSpeedMultiplier(state) : 1.0f;
        float pickaxeSpeed = isPickaxeItem(pickaxeStack) ? pickaxeStack.getMiningSpeedMultiplier(state) : 1.0f;

        if (axeSpeed > pickaxeSpeed && axeSpeed > 1.0f) {
            ensureAxeInHand(client);
        } else if (pickaxeSpeed > 1.0f) {
            ensurePickaxeInHand(client);
        } else if (isAxeBlock(state)) {
            ensureAxeInHand(client);
        } else {
            ensurePickaxeInHand(client);
        }
    }

    public static boolean isAxeBlock(BlockState state) {
        if (state.getBlock() instanceof AbstractSignBlock) return true;
        String name = state.getBlock().toString().toLowerCase();
        return name.contains("sign") || name.contains("banner") || name.contains("wood") 
                || name.contains("log") || name.contains("plank") || name.contains("door") 
                || name.contains("fence") || name.contains("gate") || name.contains("barrel")
                || name.contains("chest") || name.contains("bookshelf") || name.contains("crafting")
                || name.contains("pumpkin") || name.contains("melon");
    }

    private static boolean selectSlot(MinecraftClient client, int slot) {
        if (client.player != null && client.player.getInventory().getSelectedSlot() != slot) {
            client.player.getInventory().setSelectedSlot(slot);
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            }
            toolSwitchCooldown = 1;
            return true;
        }
        return false;
    }

    public static boolean ensureSwordInHand(MinecraftClient client) {
        if (client.player == null) return false;
        if (isSwordItem(client.player.getMainHandStack())) return true;

        ItemStack slotStack = client.player.getInventory().getStack(SLOT_SWORD);
        if (isSwordItem(slotStack)) {
            selectSlot(client, SLOT_SWORD);
            return false;
        }

        for (int i = 0; i < 9; i++) {
            if (isSwordItem(client.player.getInventory().getStack(i))) {
                selectSlot(client, i);
                return false;
            }
        }

        for (int i = 9; i <= 35; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (isSwordItem(stack)) {
                int syncId = client.player.playerScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, i, SLOT_SWORD, SlotActionType.SWAP, client.player);
                toolSwitchCooldown = 2;
                selectSlot(client, SLOT_SWORD);
                return false;
            }
        }
        return false;
    }

    public static boolean ensurePickaxeInHand(MinecraftClient client) {
        if (client.player == null) return false;
        if (isPickaxeItem(client.player.getMainHandStack())) return true;

        ItemStack slotStack = client.player.getInventory().getStack(SLOT_PICKAXE);
        if (isPickaxeItem(slotStack)) return selectSlot(client, SLOT_PICKAXE);

        for (int i = 0; i <= 35; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (isPickaxeItem(stack)) {
                if (i < 9) {
                    return selectSlot(client, i);
                } else {
                    int syncId = client.player.playerScreenHandler.syncId;
                    client.interactionManager.clickSlot(syncId, i, SLOT_PICKAXE, SlotActionType.SWAP, client.player);
                    toolSwitchCooldown = 2;
                    return selectSlot(client, SLOT_PICKAXE);
                }
            }
        }
        return false;
    }

    public static boolean ensureAxeInHand(MinecraftClient client) {
        if (client.player == null) return false;
        if (isAxeItem(client.player.getMainHandStack())) return true;

        ItemStack slotStack = client.player.getInventory().getStack(SLOT_AXE);
        if (isAxeItem(slotStack)) return selectSlot(client, SLOT_AXE);

        for (int i = 0; i <= 35; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (isAxeItem(stack)) {
                if (i < 9) {
                    return selectSlot(client, i);
                } else {
                    int syncId = client.player.playerScreenHandler.syncId;
                    client.interactionManager.clickSlot(syncId, i, SLOT_AXE, SlotActionType.SWAP, client.player);
                    toolSwitchCooldown = 2;
                    return selectSlot(client, SLOT_AXE);
                }
            }
        }
        return false;
    }

    public static boolean isPickaxeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem().toString().toLowerCase().contains("pickaxe");
    }

    public static boolean isAxeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String name = stack.getItem().toString().toLowerCase();
        return (name.contains("_axe") || name.endsWith("axe")) && !name.contains("pickaxe");
    }

    public static boolean isSwordItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem().toString().toLowerCase().contains("sword");
    }
}