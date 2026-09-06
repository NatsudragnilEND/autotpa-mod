package com.example.autotpa;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoSellManager {

    public enum SellState {
        IDLE, PREPARING_SHOP, WAITING_SHOP_OPEN, SCANNING_SHOP_AND_BUYING, 
        PREPARING_SELL, WAITING_SELL_OPEN, FILLING_SELL_MENU, CLICKING_CONFIRM_SELL, 
        WAITING_AFTER_SELL, 
        AH_START_ITEM, AH_SEARCH_WAIT_OPEN, AH_SEARCH_READ_PRICE,
        AH_STEP1_SWAP_TO_HAND, AH_STEP2_VERIFY_AND_SEND, AH_STEP3_RESTORE_WEAPON, 
        CHECKING_GROUND_LOOT,
        DEATH_OPEN_SHOP, DEATH_BUYING, DEATH_SORTING,
        BUYING_SWORD,
        OPENING_ECHEST_FOR_SPAWNERS, STORING_SPAWNERS
    }

    public static SellState currentState = SellState.IDLE;
    private static int stateDelayTicks = 0;
    public static int globalCooldown = 0;
    private static int guiOpenTimeout = 0;
    private static int restockCheckCooldown = 0;
    private static int payCooldownTicks = 0;
    private static int sessionWatchdogTicks = 0;
    private static int consecutiveGuiFailures = 0;

    // --- ПЕРЕМЕННЫЕ ПОШАГОВОЙ БЕЗОПАСНОЙ СОРТИРОВКИ ДЛЯ АНТИЧИТА ---
    private static int sortSubState = 0; // 0: Поиск/Взять, 1: Положить в цель, 2: Вернуть остаток
    private static int pendingFromSlot = -1;
    private static int pendingToSlot = -1;

    private static Item ahExpectedItem = null;
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?i)(?:\\$|цена|price|cost)?\\s*~?\\s*\\$?\\s*([0-9.,]+(?:\\s*[kKmMbB])?)");

    private record NormalizedGearKey(Item item, String enchantsKey) {}
    private static final Map<NormalizedGearKey, String> dynamicShopPrices = new HashMap<>();
    private static final Map<Item, Integer> pendingShopBuys = new HashMap<>();

    private static final List<Integer> sellSlotQueue = new ArrayList<>();
    private static final List<AhItemTarget> ahItemQueue = new ArrayList<>();
    private static int currentAhIndex = 0;

    public static class AhItemTarget {
        final int inventorySlotIndex;
        final Item expectedItem;
        String priceString;
        final String searchQuery;

        AhItemTarget(int inventorySlotIndex, Item expectedItem, String priceString, String searchQuery) {
            this.inventorySlotIndex = inventorySlotIndex;
            this.expectedItem = expectedItem;
            this.priceString = priceString;
            this.searchQuery = searchQuery;
        }
    }

    public static boolean isSpawner(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isOf(Items.SPAWNER)) return true;
        String name = Autotpa.cleanText(stack.getName().getString()).toLowerCase();
        if (name.contains("spawner") || name.contains("спавнер")) return true;
        String transKey = stack.getItem().getTranslationKey().toLowerCase();
        return transKey.contains("spawner") || transKey.contains("спавнер");
    }

    public static boolean hasSpawnersInInventory(MinecraftClient client) {
        if (client.player == null) return false;
        for (int i = 0; i <= 35; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (isSpawner(stack)) return true;
        }
        return false;
    }

    public static BlockPos findNearestEnderChest(MinecraftClient client) {
        if (client.world == null || client.player == null) return null;
        BlockPos playerPos = client.player.getBlockPos();
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos p = playerPos.add(x, y, z);
                    if (client.world.getBlockState(p).isOf(Blocks.ENDER_CHEST)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    public static void startDepositSpawners(MinecraftClient client) {
        if (client.player == null) return;
        currentState = SellState.OPENING_ECHEST_FOR_SPAWNERS;
        stateDelayTicks = 4;
        Autotpa.sendFeedback("§e[EnderChest] Обнаружен спавнер! Открываю эндер-сундук...");

        BlockPos echestPos = findNearestEnderChest(client);
        if (echestPos != null && client.interactionManager != null) {
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(echestPos), Direction.UP, echestPos, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            client.player.swingHand(Hand.MAIN_HAND);
        } else {
            CommandQueue.send("echest");
        }
    }

    public static void startAutoSell(MinecraftClient client) {
        if (!Autotpa.isMasterBotEnabled || currentState != SellState.IDLE) return;
        if (globalCooldown > 0) return;
        if (CombatManager.isServerCombatActive()) return;

        if (hasSpawnersInInventory(client)) {
            startDepositSpawners(client);
            return;
        }

        currentState = SellState.PREPARING_SELL;
        stateDelayTicks = 2;
        pendingShopBuys.clear();
        sessionWatchdogTicks = 0;
        Autotpa.sendFeedback("§6[AutoSell] §eЗапуск быстрой продажи лута (/sell)...");
    }

    public static void forceStartAutoSell(MinecraftClient client) {
        if (!Autotpa.isMasterBotEnabled || currentState != SellState.IDLE) return;
        if (CombatManager.isServerCombatActive()) return;

        if (hasSpawnersInInventory(client)) {
            startDepositSpawners(client);
            return;
        }

        globalCooldown = 0;
        startAutoSell(client);
    }

    public static void startInitialRegear(MinecraftClient client) {
        currentState = SellState.DEATH_OPEN_SHOP;
        stateDelayTicks = 4;
        globalCooldown = 0;
        pendingShopBuys.clear();
        sessionWatchdogTicks = 0;
        sortSubState = 0;
        Autotpa.sendFeedback("§e[MasterBot] Запуск авто-закупки экипировки...");
    }

    public static void startDeathRecovery(MinecraftClient client) {
        currentState = SellState.DEATH_OPEN_SHOP;
        stateDelayTicks = 15; 
        globalCooldown = 9999; 
        pendingShopBuys.clear();
        sessionWatchdogTicks = 0;
        sortSubState = 0;
        Autotpa.sendFeedback("§c[AutoRecover] Смерть зафиксирована! Закупка экипировки...");
    }

    public static void startRestock(MinecraftClient client, String missingReason) {
        currentState = SellState.DEATH_OPEN_SHOP;
        stateDelayTicks = 4;
        globalCooldown = 60;
        pendingShopBuys.clear();
        sessionWatchdogTicks = 0;
        sortSubState = 0;
        Autotpa.sendFeedback("§e[AutoRestock] Не хватает: §c" + missingReason + "§e! Докупаю в /shop...");
    }

    public static void startSwordRestock(MinecraftClient client) {
        currentState = SellState.BUYING_SWORD;
        CommandQueue.send("shop");
        stateDelayTicks = 10;
    }

    public static void abortAutoSell(MinecraftClient client) {
        currentState = SellState.IDLE;
        sellSlotQueue.clear();
        ahItemQueue.clear();
        currentAhIndex = 0;
        stateDelayTicks = 0;
        sessionWatchdogTicks = 0;
        sortSubState = 0;
        pendingFromSlot = -1;
        pendingToSlot = -1;
        globalCooldown = 60;
        CommandQueue.clear();

        if (client != null && client.player != null) {
            if (client.currentScreen != null) {
                client.player.closeHandledScreen();
                client.setScreen(null);
            }
            selectHotbarSlot(client, 0);
        }
    }

    public static boolean isDeathRecoveryState() {
        return (currentState == SellState.DEATH_OPEN_SHOP || currentState == SellState.DEATH_BUYING || currentState == SellState.DEATH_SORTING);
    }

    public static void tick(MinecraftClient client) {
        if (!Autotpa.isMasterBotEnabled) {
            currentState = SellState.IDLE;
            return;
        }

        if (CombatManager.isCombatActive(client) || CombatManager.findTarget(client) != null) {
            if (currentState != SellState.IDLE || client.currentScreen != null) {
                Autotpa.sendFeedback("§4§l[ТРЕВОГА] ВРАГ В ЛОВУШКЕ! МГНОВЕННО ЗАКРЫВАЮ МЕНЮ И В БОЙ!");
                abortAutoSell(client);
            }
            return;
        }

        if (BlockInteractionHelper.isBreaking() || CombatManager.isPostKillProcessing()) {
            if (currentState == SellState.IDLE) {
                return;
            }
        }

        if (currentState != SellState.IDLE) {
            sessionWatchdogTicks++;
            if (sessionWatchdogTicks > 600) { 
                Autotpa.sendFeedback("§c[AutoSell] Торговля застряла. Сброс...");
                abortAutoSell(client);
                return;
            }
        } else {
            sessionWatchdogTicks = 0;
        }

        boolean isRegearing = (Autotpa.currentBotState == Autotpa.BotState.STARTING_REGEAR);

        if (TpaManager.isWaitingItzNatsuu || (Autotpa.currentBotState != Autotpa.BotState.IDLE_TRAPPING && !isRegearing)) {
            if (currentState != SellState.IDLE && !isDeathRecoveryState()) {
                currentState = SellState.IDLE;
                if (client.currentScreen != null) client.player.closeHandledScreen();
            }
            return;
        }

        boolean isDeathRecovery = isDeathRecoveryState();
        if (globalCooldown > 0 && !isDeathRecovery) globalCooldown--;

        if (currentState == SellState.IDLE) {
            if (CombatManager.isRepairingTrap || BlockInteractionHelper.isBreaking()) {
                return;
            }

            checkBalanceAndAutoPay(client);
            ensureBowInSlot6(client);

            if (hasSpawnersInInventory(client)) {
                startDepositSpawners(client);
                return;
            }

            if (globalCooldown <= 0 && !CombatManager.isServerCombatActive()) {
                String missing = getRestockMissingReason(client);
                if (missing != null) {
                    startRestock(client, missing);
                } else if (hasSellableItems(client)) {
                    startAutoSell(client);
                }
            }
            return;
        }

        if (stateDelayTicks > 0) {
            stateDelayTicks--;
            return;
        }

        if (client.player == null || client.interactionManager == null) {
            currentState = SellState.IDLE;
            return;
        }

        switch (currentState) {
            case OPENING_ECHEST_FOR_SPAWNERS -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    currentState = SellState.STORING_SPAWNERS;
                    stateDelayTicks = 2;
                } else {
                    BlockPos echestPos = findNearestEnderChest(client);
                    if (echestPos != null) {
                        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(echestPos), Direction.UP, echestPos, false);
                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                        client.player.swingHand(Hand.MAIN_HAND);
                    } else {
                        CommandQueue.send("echest");
                    }
                    stateDelayTicks = 10;
                }
            }

            case STORING_SPAWNERS -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    int syncId = screen.getScreenHandler().syncId;
                    int totalSlots = screen.getScreenHandler().slots.size();
                    int chestSlots = (totalSlots >= 90) ? 54 : 27;

                    boolean movedAny = false;
                    for (int i = chestSlots; i < totalSlots; i++) {
                        ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
                        if (isSpawner(stack)) {
                            client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                            movedAny = true;
                        }
                    }

                    client.player.closeHandledScreen();
                    if (movedAny) {
                        Autotpa.sendFeedback("§a[EnderChest] Спавнер(ы) успешно спрятаны в эндер-сундук!");
                    }
                }
                currentState = SellState.IDLE;
                globalCooldown = 20;
            }

            case BUYING_SWORD -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    int totalSlots = screen.getScreenHandler().slots.size();
                    int chestSize = (totalSlots >= 90) ? 54 : (totalSlots >= 36 ? totalSlots - 36 : 27);
                    int maxShopItemSlot = Math.max(0, chestSize - 9);

                    for (int i = 0; i < maxShopItemSlot; i++) {
                        ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
                        if (isMaxedSword(client, stack)) {
                            client.interactionManager.clickSlot(screen.getScreenHandler().syncId, i, 0, SlotActionType.PICKUP, client.player);
                            client.player.closeHandledScreen();
                            currentState = SellState.IDLE;
                            globalCooldown = 60;
                            Autotpa.sendFeedback("§a[Shop] Куплен топовый меч!");
                            return;
                        }
                    }
                    CommandQueue.send("shop");
                    stateDelayTicks = 15;
                } else {
                    CommandQueue.send("shop");
                    stateDelayTicks = 15;
                }
            }

            case DEATH_OPEN_SHOP -> {
                if (client.player.isDead() || client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
                    client.player.requestRespawn();
                    if (client.currentScreen != null) client.setScreen(null);
                    stateDelayTicks = 15;
                    return;
                }
                if (client.currentScreen != null) client.player.closeHandledScreen();
                CommandQueue.send("shop");
                currentState = SellState.DEATH_BUYING;
                stateDelayTicks = 15;
            }

            case DEATH_BUYING -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    boolean boughtSomething = processDeathShopBuy(client, screen);
                    if (!boughtSomething) {
                        client.player.closeHandledScreen();
                        pendingShopBuys.clear();
                        currentState = SellState.DEATH_SORTING;
                        sortSubState = 0;
                        stateDelayTicks = 8;
                    } else {
                        sessionWatchdogTicks = 0;
                        stateDelayTicks = 8; 
                    }
                } else {
                    CommandQueue.send("shop");
                    stateDelayTicks = 15;
                }
            }

            case DEATH_SORTING -> {
                if (client.currentScreen != null && !(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
                    client.player.closeHandledScreen();
                }
                boolean done = performOneSortStep(client);
                if (done) {
                    CombatManager.equipArmorFromInventory(client);
                    Autotpa.sendFeedback("§a[MasterBot] Закупка и экипировка завершены! Возвращаюсь к ловле.");
                    currentState = SellState.IDLE;
                    globalCooldown = 40;
                    Autotpa.currentBotState = Autotpa.BotState.IDLE_TRAPPING;
                    if (!CombatManager.isAtTrap(client)) {
                        CombatManager.sendHome1WithVerification(client);
                    }
                } else {
                    sessionWatchdogTicks = 0;
                }
            }

            case PREPARING_SELL -> {
                analyzeInventory(client);
                if (!sellSlotQueue.isEmpty()) {
                    CommandQueue.send("sell");
                    currentState = SellState.WAITING_SELL_OPEN;
                    guiOpenTimeout = 70; 
                    stateDelayTicks = 2;
                } else if (!ahItemQueue.isEmpty()) {
                    currentAhIndex = 0;
                    currentState = SellState.AH_START_ITEM;
                    stateDelayTicks = 3;
                } else {
                    currentState = SellState.IDLE;
                    globalCooldown = 60;
                    stateDelayTicks = 1;
                }
            }

            case WAITING_SELL_OPEN -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    consecutiveGuiFailures = 0;
                    currentState = SellState.FILLING_SELL_MENU;
                    stateDelayTicks = 3;
                } else if (guiOpenTimeout > 0) {
                    guiOpenTimeout--;
                    stateDelayTicks = 1;
                } else {
                    consecutiveGuiFailures++;
                    if (consecutiveGuiFailures >= 2) {
                        Autotpa.sendFeedback("§c[AutoSell] Меню /sell не ответило. Пропускаю.");
                        consecutiveGuiFailures = 0;
                        currentState = SellState.IDLE;
                        globalCooldown = 120;
                        return;
                    }
                    currentState = SellState.IDLE;
                    globalCooldown = 60; 
                }
            }

            case FILLING_SELL_MENU -> {
                if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
                    currentState = SellState.IDLE;
                    globalCooldown = 60;
                    return;
                }
                
                int totalSlots = screen.getScreenHandler().slots.size();
                int chestSize = (totalSlots >= 90) ? 54 : (totalSlots >= 63 ? 27 : 0);

                if (chestSize > 0 && !sellSlotQueue.isEmpty()) {
                    int invSlot = sellSlotQueue.remove(0);
                    int containerSlot = chestSize + (invSlot - 9);
                    if (containerSlot < totalSlots) {
                        client.interactionManager.clickSlot(screen.getScreenHandler().syncId, containerSlot, 0, SlotActionType.QUICK_MOVE, client.player);
                        sessionWatchdogTicks = 0;
                    }
                }

                if (sellSlotQueue.isEmpty() || chestSize == 0) {
                    currentState = SellState.CLICKING_CONFIRM_SELL;
                    stateDelayTicks = 5;
                } else {
                    stateDelayTicks = 3; // 150ms между предметами (защита от FastClick античита)
                }
            }

            case CLICKING_CONFIRM_SELL -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    int totalSlots = screen.getScreenHandler().slots.size();
                    int confirmSlot = (totalSlots >= 90) ? 53 : (totalSlots >= 63 ? 26 : -1);
                    if (confirmSlot != -1) {
                        client.interactionManager.clickSlot(screen.getScreenHandler().syncId, confirmSlot, 0, SlotActionType.PICKUP, client.player);
                    }
                    client.player.closeHandledScreen();
                    Autotpa.sendFeedback("§a[AutoSell] Лут успешно продан через /sell!");
                }
                currentState = SellState.WAITING_AFTER_SELL;
                stateDelayTicks = 5;
            }

            case WAITING_AFTER_SELL -> {
                if (!ahItemQueue.isEmpty()) {
                    currentAhIndex = 0;
                    currentState = SellState.AH_START_ITEM;
                    stateDelayTicks = 3;
                } else {
                    currentState = SellState.IDLE;
                    globalCooldown = 60;
                    stateDelayTicks = 1;
                }
            }

            case AH_START_ITEM -> {
                if (currentAhIndex < ahItemQueue.size()) {
                    AhItemTarget target = ahItemQueue.get(currentAhIndex);
                    ItemStack invStack = client.player.getInventory().getStack(target.inventorySlotIndex);
                    
                    if (invStack.isEmpty() || target.priceString == null || target.priceString.trim().isEmpty()) {
                        currentAhIndex++;
                        stateDelayTicks = 1;
                        return;
                    }

                    if (client.currentScreen != null) {
                        client.player.closeHandledScreen();
                    }

                    currentState = SellState.AH_STEP1_SWAP_TO_HAND;
                    stateDelayTicks = 3;
                } else {
                    currentState = SellState.IDLE;
                    globalCooldown = 100;
                }
            }

            case AH_STEP1_SWAP_TO_HAND -> {
                if (currentAhIndex < ahItemQueue.size()) {
                    AhItemTarget target = ahItemQueue.get(currentAhIndex);
                    ItemStack invStack = client.player.getInventory().getStack(target.inventorySlotIndex);
                    if (invStack.isEmpty()) {
                        currentAhIndex++;
                        currentState = SellState.AH_START_ITEM;
                        stateDelayTicks = 1;
                        return;
                    }

                    client.options.useKey.setPressed(false);
                    ahExpectedItem = invStack.getItem();
                    selectHotbarSlot(client, 0);

                    client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, target.inventorySlotIndex, 0, SlotActionType.SWAP, client.player);
                    stateDelayTicks = 5;
                    currentState = SellState.AH_STEP2_VERIFY_AND_SEND;
                } else {
                    currentState = SellState.IDLE;
                    globalCooldown = 100;
                }
            }
            case AH_STEP2_VERIFY_AND_SEND -> {
                if (currentAhIndex < ahItemQueue.size()) {
                    AhItemTarget target = ahItemQueue.get(currentAhIndex);
                    ItemStack inHand = client.player.getMainHandStack();

                    if (inHand.isEmpty() || (ahExpectedItem != null && inHand.getItem() != ahExpectedItem)) {
                        restoreWeapon(client, target.inventorySlotIndex);
                        currentAhIndex++;
                        currentState = SellState.AH_START_ITEM;
                        stateDelayTicks = 4;
                        return;
                    }

                    CommandQueue.send("ah sell " + target.priceString);
                    Autotpa.sendFeedback("§e[AH] Выставляю: §b" + inHand.getName().getString() + " §eза §a$" + target.priceString);

                    stateDelayTicks = 16;
                    currentState = SellState.AH_STEP3_RESTORE_WEAPON;
                } else {
                    currentState = SellState.IDLE;
                    globalCooldown = 100;
                }
            }
            case AH_STEP3_RESTORE_WEAPON -> {
                if (currentAhIndex < ahItemQueue.size()) {
                    AhItemTarget target = ahItemQueue.get(currentAhIndex);

                    restoreWeapon(client, target.inventorySlotIndex);
                    currentAhIndex++;

                    selectHotbarSlot(client, 0);
                    CombatManager.ensureSwordInHand(client);

                    currentState = SellState.AH_START_ITEM;
                    stateDelayTicks = 4;
                } else {
                    currentState = SellState.IDLE;
                    globalCooldown = 100;
                }
            }

            default -> currentState = SellState.IDLE;
        }
    }

    public static boolean isHarmingArrow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isOf(Items.TIPPED_ARROW)) return false;

        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents != null) {
            if (contents.potion().isPresent()) {
                String id = contents.potion().get().getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse("");
                if (id.contains("harming") || id.contains("damage")) return true;
            }
            for (net.minecraft.entity.effect.StatusEffectInstance effect : contents.customEffects()) {
                String effectId = effect.getEffectType().getIdAsString().toLowerCase();
                if (effectId.contains("harming") || effectId.contains("instant_damage")) return true;
            }
        }

        String name = Autotpa.cleanText(stack.getName().getString()).toLowerCase();
        if (name.contains("урон") || name.contains("harming") || name.contains("damage")) return true;

        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                String cleanLine = Autotpa.cleanText(line.getString()).toLowerCase();
                if (cleanLine.contains("урон") || cleanLine.contains("harming") || cleanLine.contains("damage")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int countHarmingArrows(MinecraftClient client) {
        if (client == null || client.player == null) return 0;
        int total = 0;
        for (int i = 0; i <= 35; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (isHarmingArrow(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static String getRestockMissingReason(MinecraftClient client) {
        if (client.player == null) return null;

        if (!CombatManager.hasSword(client)) return "Меч";
        if (!hasArmorPiece(client, "helmet")) return "Шлем";
        if (!hasArmorPiece(client, "chestplate")) return "Нагрудник";
        if (!hasArmorPiece(client, "leggings")) return "Поножи";
        if (!hasArmorPiece(client, "boots")) return "Ботинки";

        int bows = countItemInInventory(client, Items.BOW);
        if (bows < 2) return "Лук (" + bows + "/2)";

        int arrows = countHarmingArrows(client);
        if (arrows < 128) return "Стрелы урона (" + arrows + "/192)";

        int echests = countItemInInventory(client, Items.ENDER_CHEST);
        if (echests < 32) return "Эндер-сундуки (" + echests + "/64)";

        int gapples = countItemInInventory(client, Items.GOLDEN_APPLE) + countItemInInventory(client, Items.ENCHANTED_GOLDEN_APPLE);
        if (gapples < 32) return "Золотые яблоки (" + gapples + "/64)";

        int cobweb = countItemInInventory(client, Items.COBWEB);
        if (cobweb < 32) return "Паутина (" + cobweb + "/64)";

        int pearls = countItemInInventory(client, Items.ENDER_PEARL);
        if (pearls < 16) return "Эндер-жемчуг (" + pearls + "/16)";

        int totems = countItemInInventory(client, Items.TOTEM_OF_UNDYING);
        if (totems < 6) return "Тотемы (" + totems + "/8)";

        int strength = countStrengthPotions(client);
        if (strength < 4) return "Зелья силы (" + strength + "/4)";

        int xp = countItemInInventory(client, Items.EXPERIENCE_BOTTLE);
        if (xp < 32) return "Пузырьки опыта (" + xp + "/64)";

        int axes = countItemInInventory(client, Items.DIAMOND_AXE) + countItemInInventory(client, Items.NETHERITE_AXE);
        if (axes < 1) return "Топор";

        int picks = countItemInInventory(client, Items.DIAMOND_PICKAXE) + countItemInInventory(client, Items.NETHERITE_PICKAXE);
        if (picks < 1) return "Кирка";

        return null;
    }

    public static boolean isRestockNeededBeforeTrap(MinecraftClient client) {
        return getRestockMissingReason(client) != null;
    }

    public static boolean isStrengthPotion(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        if (s.getItem() != Items.POTION && s.getItem() != Items.SPLASH_POTION) return false;
        PotionContentsComponent contents = s.get(DataComponentTypes.POTION_CONTENTS);
        if (contents != null) {
            if (contents.potion().isPresent()) {
                String id = contents.potion().get().getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse("");
                if (id.contains("strength")) return true;
            }
            for (var effect : contents.customEffects()) {
                if (effect.getEffectType().getIdAsString().toLowerCase().contains("strength")) return true;
            }
        }
        String name = Autotpa.cleanText(s.getName().getString()).toLowerCase();
        return name.contains("сил") || name.contains("strength");
    }

    public static int countStrengthPotions(MinecraftClient client) {
        int c = 0;
        if (client.player == null) return 0;
        for (int i = 0; i <= 35; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (isStrengthPotion(s)) c++;
        }
        return c;
    }

    public static int countItemInInventory(MinecraftClient client, Item item) {
    if (client.player == null) return 0;
    int count = 0;
    
    // Считаем инвентарь и хотбар (0-35)
    for (int i = 0; i <= 35; i++) {
        ItemStack s = client.player.getInventory().getStack(i);
        if (s.isOf(item)) count += s.getCount();
    }
    // Считаем вторую руку (Offhand)
    if (client.player.getOffHandStack().isOf(item)) {
        count += client.player.getOffHandStack().getCount();
    }
    // ВАЖНО: Считаем надетую броню на теле!
    for (EquipmentSlot slot : EquipmentSlot.values()) {
        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            ItemStack equipped = client.player.getEquippedStack(slot);
            if (equipped.isOf(item)) {
                count += equipped.getCount();
            }
        }
    }
    return count;
}

    private static boolean processDeathShopBuy(MinecraftClient client, HandledScreen<?> screen) {
        if (!(screen instanceof GenericContainerScreen containerScreen)) return false;

        if (clickConfirmIfPresent(client, containerScreen)) {
            return true;
        }
        int slotsCount = screen.getScreenHandler().slots.size();
        
        int chestSize = (slotsCount >= 90) ? 54 : (slotsCount >= 36 ? slotsCount - 36 : 27);
        int maxShopItemSlot = Math.max(0, chestSize - 9); 

        for (int i = 0; i < maxShopItemSlot; i++) {
            ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            double p = getItemPrice(client, stack);
            if (p > 0) {
                String nKey = getNormalizedEnchantKey(stack);
                dynamicShopPrices.put(new NormalizedGearKey(stack.getItem(), nKey), formatPrice(p));
            }
        }

        int helms = countItemInInventory(client, Items.DIAMOND_HELMET) + pendingShopBuys.getOrDefault(Items.DIAMOND_HELMET, 0);
        int chests = countItemInInventory(client, Items.DIAMOND_CHESTPLATE) + pendingShopBuys.getOrDefault(Items.DIAMOND_CHESTPLATE, 0);
        int legs = countItemInInventory(client, Items.DIAMOND_LEGGINGS) + pendingShopBuys.getOrDefault(Items.DIAMOND_LEGGINGS, 0);
        int boots = countItemInInventory(client, Items.DIAMOND_BOOTS) + pendingShopBuys.getOrDefault(Items.DIAMOND_BOOTS, 0);
        int axes = countItemInInventory(client, Items.DIAMOND_AXE) + pendingShopBuys.getOrDefault(Items.DIAMOND_AXE, 0);
        int picks = countItemInInventory(client, Items.DIAMOND_PICKAXE) + pendingShopBuys.getOrDefault(Items.DIAMOND_PICKAXE, 0);
        
        int cobwebs = countItemInInventory(client, Items.COBWEB) + pendingShopBuys.getOrDefault(Items.COBWEB, 0);
        int pearls = countItemInInventory(client, Items.ENDER_PEARL) + pendingShopBuys.getOrDefault(Items.ENDER_PEARL, 0);
        int gapples = countItemInInventory(client, Items.GOLDEN_APPLE) + countItemInInventory(client, Items.ENCHANTED_GOLDEN_APPLE) + pendingShopBuys.getOrDefault(Items.GOLDEN_APPLE, 0);
        int xp = countItemInInventory(client, Items.EXPERIENCE_BOTTLE) + pendingShopBuys.getOrDefault(Items.EXPERIENCE_BOTTLE, 0);
        int totems = countItemInInventory(client, Items.TOTEM_OF_UNDYING) + pendingShopBuys.getOrDefault(Items.TOTEM_OF_UNDYING, 0);
        
        boolean hasGoodSword = hasMaxedSwordInInventory(client) || pendingShopBuys.getOrDefault(Items.DIAMOND_SWORD, 0) > 0;
        int potions = countStrengthPotions(client) + pendingShopBuys.getOrDefault(Items.POTION, 0) + pendingShopBuys.getOrDefault(Items.SPLASH_POTION, 0);

        int currentHarmingArrows = countHarmingArrows(client) + pendingShopBuys.getOrDefault(Items.TIPPED_ARROW, 0);
        int bows = countItemInInventory(client, Items.BOW) + pendingShopBuys.getOrDefault(Items.BOW, 0);
        int echests = countItemInInventory(client, Items.ENDER_CHEST) + pendingShopBuys.getOrDefault(Items.ENDER_CHEST, 0);

        for (int i = 0; i < maxShopItemSlot; i++) {
            ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            String name = Autotpa.cleanText(stack.getName().getString()).toLowerCase();

            if (name.contains("auction") || name.contains("аукцион") || name.contains("/ah") || name.contains("рынок") || name.contains("market")) {
                continue;
            }

            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null) {
                boolean isAuctionBtn = false;
                for (Text line : lore.lines()) {
                    String clean = Autotpa.cleanText(line.getString()).toLowerCase();
                    if (clean.contains("auction") || clean.contains("аукцион") || clean.contains("/ah") || clean.contains("рынок")) {
                        isAuctionBtn = true;
                        break;
                    }
                }
                if (isAuctionBtn) continue;
            }

            boolean buy = false;
            Item buyItemKey = item;
            boolean buyStack = false;
            
            // Броня — покупаем ТОЛЬКО если ее 0 шт. (учитывая надетое на тело через hasArmorPiece)
if (item == Items.DIAMOND_HELMET && !hasArmorPiece(client, "helmet") && isMaxedArmor(client, stack)) {
    buy = true; buyItemKey = Items.DIAMOND_HELMET;
}
else if (item == Items.DIAMOND_CHESTPLATE && !hasArmorPiece(client, "chestplate") && isMaxedArmor(client, stack)) {
    buy = true; buyItemKey = Items.DIAMOND_CHESTPLATE;
}
else if (item == Items.DIAMOND_LEGGINGS && !hasArmorPiece(client, "leggings") && isMaxedArmor(client, stack)) {
    buy = true; buyItemKey = Items.DIAMOND_LEGGINGS;
}
else if (item == Items.DIAMOND_BOOTS && !hasArmorPiece(client, "boots") && isMaxedArmor(client, stack)) {
    buy = true; buyItemKey = Items.DIAMOND_BOOTS;
}
else if ((item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD) && !CombatManager.hasSword(client) && isMaxedSword(client, stack)) {
    buy = true; buyItemKey = Items.DIAMOND_SWORD;
}
else if (item == Items.DIAMOND_AXE && axes < 1) buy = true;
else if (item == Items.DIAMOND_PICKAXE && picks < 1 && isEfficiency5Pickaxe(client, stack)) {
    buy = true; buyItemKey = Items.DIAMOND_PICKAXE;
}
else if (isBowItem(stack) && bows < 2) { // 1 лука в инвентаре достаточно
    buy = true; buyItemKey = Items.BOW;
}
// Расходники — покупаем стак, только если текущий запас просел
else if (item == Items.ENDER_CHEST && echests < 16) {
    buy = true; buyItemKey = Items.ENDER_CHEST; buyStack = true;
}
else if (item == Items.COBWEB && cobwebs < 16) {
    buy = true; buyItemKey = Items.COBWEB; buyStack = true;
}
else if (item == Items.ENDER_PEARL && pearls < 8) {
    buy = true; buyItemKey = Items.ENDER_PEARL; buyStack = true;
}
else if ((item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) && gapples < 16) {
    buy = true; buyItemKey = Items.GOLDEN_APPLE; buyStack = true;
}
else if (item == Items.EXPERIENCE_BOTTLE && xp < 64) {
    buy = true; buyItemKey = Items.EXPERIENCE_BOTTLE; buyStack = true;
}
else if (item == Items.TOTEM_OF_UNDYING && totems < 6) {
    buy = true; buyItemKey = Items.TOTEM_OF_UNDYING;
}
else if (isHarmingArrow(stack) && currentHarmingArrows < 64) { // 1 полный стак (64 шт.)
    buy = true; 
    buyItemKey = Items.TIPPED_ARROW;
    buyStack = true;
}
            else if ((item == Items.POTION || item == Items.SPLASH_POTION) && potions < 4) {
                if (isStrengthPotion(stack)) {
                    double price = getItemPrice(client, stack);
                    if (item == Items.POTION && price > 100_000.0) continue;
                    buy = true;
                    buyItemKey = item;
                }
            }
            
            if (buy && hasFreeSlot(client)) {
                int amountToAdd = 1;
                if (buyStack) {
                    amountToAdd = (buyItemKey == Items.ENDER_PEARL) ? 16 : 64;
                    client.interactionManager.clickSlot(screen.getScreenHandler().syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                    Autotpa.sendFeedback("§a[Shop] Покупка стака: " + stack.getName().getString());
                } else {
                    client.interactionManager.clickSlot(screen.getScreenHandler().syncId, i, 0, SlotActionType.PICKUP, client.player);
                    Autotpa.sendFeedback("§a[Shop] Куплен предмет: §e" + stack.getName().getString());
                }
                pendingShopBuys.put(buyItemKey, pendingShopBuys.getOrDefault(buyItemKey, 0) + amountToAdd);
                return true;
            }
        }
        return false;
    }

    public static void ensureBowInSlot6(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        ItemStack slot6Stack = client.player.getInventory().getStack(6);
        if (isBowItem(slot6Stack)) return;

        int syncId = client.player.playerScreenHandler.syncId;

        for (int i = 0; i <= 35; i++) {
            if (i == 6) continue;
            ItemStack s = client.player.getInventory().getStack(i);
            if (isBowItem(s)) {
                int containerSlot = (i < 9) ? (36 + i) : i;
                client.interactionManager.clickSlot(syncId, containerSlot, 6, SlotActionType.SWAP, client.player);
                return;
            }
        }
    }

    // =========================================================================
    // АНТИЧИТ-БЕЗОПАСНАЯ ПОШАГОВАЯ СОРТИРОВКА (1 ДЕЙСТВИЕ В 200-250 МС)
    // =========================================================================
    private static boolean isAlreadyInCorrectSlot(int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();

        if (slot == 5 && matchesType(item, "helmet")) return true;
        if (slot == 6 && matchesType(item, "chestplate")) return true;
        if (slot == 7 && matchesType(item, "leggings")) return true;
        if (slot == 8 && matchesType(item, "boots")) return true;
        if (slot == 45 && stack.isOf(Items.TOTEM_OF_UNDYING)) return true;

        if (slot == 36 && matchesType(item, "sword")) return true;
        if (slot == 37 && stack.isOf(Items.COBWEB)) return true;
        if (slot == 38 && matchesType(item, "axe")) return true;
        if (slot == 39 && stack.isOf(Items.ENDER_PEARL)) return true;
        if (slot == 40 && (stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE))) return true;
        if (slot == 41 && stack.isOf(Items.ENDER_CHEST)) return true;
        if (slot == 42 && isBowItem(stack)) return true;
        if (slot == 43 && matchesType(item, "pickaxe")) return true;
        if (slot == 44 && stack.isOf(Items.TOTEM_OF_UNDYING)) return true;

        if ((slot == 9 || slot == 10 || slot == 29) && isHarmingArrow(stack)) return true;
        if (slot == 11 && isBowItem(stack)) return true;
        if ((slot == 12 || slot == 13 || slot == 14 || slot == 21 || slot == 22 || slot == 23) && stack.isOf(Items.TOTEM_OF_UNDYING)) return true;
        if ((slot == 15 || slot == 16 || slot == 20 || slot == 28) && isStrengthPotion(stack)) return true;
        if (slot == 17 && stack.isOf(Items.EXPERIENCE_BOTTLE)) return true;

        return false;
    }

    private interface SlotPredicate {
        boolean test(ItemStack stack);
    }

    private static boolean performOneSortStep(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return true;
        int syncId = client.player.playerScreenHandler.syncId;

        // 1. Фазы переноса предмета мышью в рюкзаке (с человеческими задержками)
        if (sortSubState == 1) {
            client.interactionManager.clickSlot(syncId, pendingToSlot, 0, SlotActionType.PICKUP, client.player);
            if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
                sortSubState = 2; // Возвращаем вытесненный предмет назад
            } else {
                sortSubState = 0;
                pendingFromSlot = -1;
                pendingToSlot = -1;
            }
            stateDelayTicks = 4; // 200 мс
            return false;
        }

        if (sortSubState == 2) {
            client.interactionManager.clickSlot(syncId, pendingFromSlot, 0, SlotActionType.PICKUP, client.player);
            sortSubState = 0;
            pendingFromSlot = -1;
            pendingToSlot = -1;
            stateDelayTicks = 4; // 200 мс
            return false;
        }

        // Если курсор почему-то не пустой — сбрасываем предмет в первый свободный слот
        if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            for (int i = 9; i <= 44; i++) {
                if (client.player.playerScreenHandler.getSlot(i).getStack().isEmpty()) {
                    client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.PICKUP, client.player);
                    stateDelayTicks = 4;
                    return false;
                }
            }
        }

        // 2. Экипировка брони (слоты 5, 6, 7, 8) - Shift-клик (250 мс)
        if (equipArmorStep(client, "helmet", 5)) return false;
        if (equipArmorStep(client, "chestplate", 6)) return false;
        if (equipArmorStep(client, "leggings", 7)) return false;
        if (equipArmorStep(client, "boots", 8)) return false;

        // 3. Тотем в левую руку (эмуляция клавиши F) (250 мс)
        if (enforceOffhandTotemStep(client)) return false;

        // 4. ХОТБАР (слоты 36..44) - Эмуляция клавиш 1-9 (1 пакет SWAP, 250 мс)
        if (enforceHotbarSlot(client, 36, s -> matchesType(s.getItem(), "sword"))) return false;
        if (enforceHotbarSlot(client, 37, s -> s.isOf(Items.COBWEB))) return false;
        if (enforceHotbarSlot(client, 38, s -> matchesType(s.getItem(), "axe"))) return false;
        if (enforceHotbarSlot(client, 39, s -> s.isOf(Items.ENDER_PEARL))) return false;
        if (enforceHotbarSlot(client, 40, s -> s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE))) return false;
        if (enforceHotbarSlot(client, 41, s -> s.isOf(Items.ENDER_CHEST))) return false;
        if (enforceHotbarSlot(client, 42, s -> isBowItem(s))) return false;
        if (enforceHotbarSlot(client, 43, s -> matchesType(s.getItem(), "pickaxe"))) return false;
        if (enforceHotbarSlot(client, 44, s -> s.isOf(Items.TOTEM_OF_UNDYING))) return false;

        // 5. ОСНОВНОЙ РЮКЗАК (слоты 9..35) - Пошаговый перенос курсором (200 мс)
        if (enforceBackpackSlot(client, 9, s -> isHarmingArrow(s))) return false;
        if (enforceBackpackSlot(client, 10, s -> isHarmingArrow(s))) return false;
        if (enforceBackpackSlot(client, 11, s -> isBowItem(s))) return false;
        if (enforceBackpackSlot(client, 12, s -> s.isOf(Items.TOTEM_OF_UNDYING))) return false;
        if (enforceBackpackSlot(client, 13, s -> s.isOf(Items.TOTEM_OF_UNDYING))) return false;
        if (enforceBackpackSlot(client, 14, s -> s.isOf(Items.TOTEM_OF_UNDYING))) return false;
        if (enforceBackpackSlot(client, 15, s -> isStrengthPotion(s))) return false;
        if (enforceBackpackSlot(client, 16, s -> isStrengthPotion(s))) return false;
        if (enforceBackpackSlot(client, 17, s -> s.isOf(Items.EXPERIENCE_BOTTLE))) return false;

        if (enforceBackpackSlot(client, 20, s -> isStrengthPotion(s))) return false;
        if (enforceBackpackSlot(client, 21, s -> s.isOf(Items.TOTEM_OF_UNDYING))) return false;
        if (enforceBackpackSlot(client, 22, s -> s.isOf(Items.TOTEM_OF_UNDYING))) return false;
        if (enforceBackpackSlot(client, 23, s -> s.isOf(Items.TOTEM_OF_UNDYING))) return false;

        if (enforceBackpackSlot(client, 28, s -> isStrengthPotion(s))) return false;
        if (enforceBackpackSlot(client, 29, s -> isHarmingArrow(s))) return false;

        return true; 
    }

    private static boolean enforceHotbarSlot(MinecraftClient client, int targetSyncSlot, SlotPredicate predicate) {
        ItemStack current = client.player.playerScreenHandler.getSlot(targetSyncSlot).getStack();
        if (predicate.test(current)) return false;

        int hotbarIndex = targetSyncSlot - 36;
        for (int i = 9; i <= 44; i++) {
            if (i == targetSyncSlot) continue;
            ItemStack candidate = client.player.playerScreenHandler.getSlot(i).getStack();
            if (candidate.isEmpty()) continue;

            if (predicate.test(candidate) && !isAlreadyInCorrectSlot(i, candidate)) {
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, i, hotbarIndex, SlotActionType.SWAP, client.player);
                stateDelayTicks = 5; // 250 мс задержка
                return true;
            }
        }
        return false;
    }

    private static boolean enforceBackpackSlot(MinecraftClient client, int targetSyncSlot, SlotPredicate predicate) {
        ItemStack current = client.player.playerScreenHandler.getSlot(targetSyncSlot).getStack();
        if (predicate.test(current)) return false;

        for (int i = 9; i <= 44; i++) {
            if (i == targetSyncSlot) continue;
            ItemStack candidate = client.player.playerScreenHandler.getSlot(i).getStack();
            if (candidate.isEmpty()) continue;

            if (predicate.test(candidate) && !isAlreadyInCorrectSlot(i, candidate)) {
                pendingFromSlot = i;
                pendingToSlot = targetSyncSlot;
                sortSubState = 1;
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, pendingFromSlot, 0, SlotActionType.PICKUP, client.player);
                stateDelayTicks = 4; // 200 мс задержка
                return true;
            }
        }
        return false;
    }

    private static boolean enforceOffhandTotemStep(MinecraftClient client) {
        if (client.player == null) return false;
        if (client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return false;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = client.player.playerScreenHandler.getSlot(i).getStack();
            if (s.isOf(Items.TOTEM_OF_UNDYING) && !isAlreadyInCorrectSlot(i, s)) {
                // Эмуляция клавиши F (кнопка 40)
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, i, 40, SlotActionType.SWAP, client.player);
                stateDelayTicks = 5; // 250 мс задержка
                return true;
            }
        }
        return false;
    }

    private static boolean equipArmorStep(MinecraftClient client, String type, int syncId) {
        ItemStack current = client.player.playerScreenHandler.getSlot(syncId).getStack();
        if (matchesType(current.getItem(), type)) return false;
        for (int i = 9; i <= 44; i++) {
            if (matchesType(client.player.playerScreenHandler.getSlot(i).getStack().getItem(), type)) {
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                stateDelayTicks = 5; // 250 мс задержка
                return true; 
            }
        }
        return false;
    }

    private static void analyzeInventory(MinecraftClient client) {
        if (client.player == null) return;
        sellSlotQueue.clear();
        ahItemQueue.clear();
        int strength = 0;

        int keptBows = 0;
        for (int i = 0; i < 9; i++) {
            if (isBowItem(client.player.getInventory().getStack(i))) keptBows++;
        }

        int keptHarmingArrows = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack hotbarStack = client.player.getInventory().getStack(i);
            if (isHarmingArrow(hotbarStack)) {
                keptHarmingArrows += hotbarStack.getCount();
            }
        }
        if (isHarmingArrow(client.player.getOffHandStack())) {
            keptHarmingArrows += client.player.getOffHandStack().getCount();
        }

        int keptGapples = 0, keptCobweb = 0, keptPearls = 0, keptXp = 0, keptEchests = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE)) keptGapples += s.getCount();
            if (s.isOf(Items.COBWEB)) keptCobweb += s.getCount();
            if (s.isOf(Items.ENDER_PEARL)) keptPearls += s.getCount();
            if (s.isOf(Items.EXPERIENCE_BOTTLE)) keptXp += s.getCount();
            if (s.isOf(Items.ENDER_CHEST)) keptEchests += s.getCount();
        }

        int protectedSwordSlot = -1, protectedPickaxeSlot = -1, protectedAxeSlot = -1;
        int protectedHelmetSlot = -1, protectedChestplateSlot = -1, protectedLeggingsSlot = -1, protectedBootsSlot = -1;

        for (int slot = 0; slot <= 35; slot++) {
            ItemStack s = client.player.getInventory().getStack(slot);
            if (s.isEmpty()) continue;
            Item item = s.getItem();

            if (protectedSwordSlot == -1 && (isMaxedSword(client, s) || matchesType(item, "sword"))) protectedSwordSlot = slot;
            if (protectedPickaxeSlot == -1 && matchesType(item, "pickaxe")) protectedPickaxeSlot = slot;
            if (protectedAxeSlot == -1 && matchesType(item, "axe")) protectedAxeSlot = slot;

            if (protectedHelmetSlot == -1 && matchesType(item, "helmet")) protectedHelmetSlot = slot;
            if (protectedChestplateSlot == -1 && matchesType(item, "chestplate")) protectedChestplateSlot = slot;
            if (protectedLeggingsSlot == -1 && matchesType(item, "leggings")) protectedLeggingsSlot = slot;
            if (protectedBootsSlot == -1 && matchesType(item, "boots")) protectedBootsSlot = slot;
        }

        int equippedTotems = 0;
        if (client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) equippedTotems++;
        if (client.player.getInventory().getStack(8).isOf(Items.TOTEM_OF_UNDYING)) equippedTotems++;
        int maxBackpackTotems = 8;
        int keptTotems = equippedTotems;

        for (int slot = 9; slot <= 35; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            if (slot == protectedSwordSlot || slot == protectedPickaxeSlot || slot == protectedAxeSlot
                    || slot == protectedHelmetSlot || slot == protectedChestplateSlot
                    || slot == protectedLeggingsSlot || slot == protectedBootsSlot) {
                continue;
            }

            if (isMaxedArmor(client, stack)) {
                continue;
            }

            if (isSpawner(stack)) {
                continue;
            }

            if (item == Items.GLASS_BOTTLE || item == Items.OBSIDIAN) {
                sellSlotQueue.add(slot);
                continue;
            }

            if (item == Items.ENDER_CHEST) {
                if (keptEchests < 64) {
                    keptEchests += stack.getCount();
                    continue;
                }
                sellSlotQueue.add(slot);
                continue;
            }

            if (isBowItem(stack)) {
                if (keptBows < 2) {
                    keptBows++;
                    continue;
                }
                sellSlotQueue.add(slot);
                continue;
            }

            if (item == Items.TOTEM_OF_UNDYING) {
                if (keptTotems < maxBackpackTotems) {
                    keptTotems++;
                    continue;
                }
                sellSlotQueue.add(slot);
                continue;
            }

            if (matchesType(item, "helmet") || matchesType(item, "chestplate") || matchesType(item, "leggings") || matchesType(item, "boots")) {
                String armorAhPrice = getArmorAhPrice(client, stack);
                if (armorAhPrice != null) {
                    ahItemQueue.add(new AhItemTarget(slot, item, armorAhPrice, null));
                }
                continue;
            }

            if (matchesType(item, "sword") || matchesType(item, "pickaxe") || matchesType(item, "axe") || matchesType(item, "shovel") || matchesType(item, "hoe")) {
                String toolAhPrice = getToolAhPrice(client, stack);
                if (toolAhPrice != null) {
                    ahItemQueue.add(new AhItemTarget(slot, item, toolAhPrice, null));
                }
                continue;
            }

            if (item == Items.ARROW || item == Items.SPECTRAL_ARROW) {
                sellSlotQueue.add(slot);
                continue;
            }

            if (item == Items.TIPPED_ARROW) {
                if (!isHarmingArrow(stack)) {
                    sellSlotQueue.add(slot);
                    continue;
                }
                if (keptHarmingArrows < 192) {
                    keptHarmingArrows += stack.getCount();
                    continue;
                } else {
                    sellSlotQueue.add(slot);
                    continue;
                }
            }

            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                if (keptGapples < 64) {
                    keptGapples += stack.getCount();
                    continue;
                }
                sellSlotQueue.add(slot);
                continue;
            }
            if (item == Items.COBWEB) {
                if (keptCobweb < 64) {
                    keptCobweb += stack.getCount();
                    continue;
                }
                sellSlotQueue.add(slot);
                continue;
            }
            if (item == Items.ENDER_PEARL) {
                if (keptPearls < 16) {
                    keptPearls += stack.getCount();
                    continue;
                }
                sellSlotQueue.add(slot);
                continue;
            }
            if (item == Items.EXPERIENCE_BOTTLE) {
                if (keptXp < 64) {
                    keptXp += stack.getCount();
                    continue;
                }
                sellSlotQueue.add(slot);
                continue;
            }
            if (isStrengthPotion(stack)) {
                if (strength < 4) { strength++; continue; } 
            }

            sellSlotQueue.add(slot);
        }
    }

    public static boolean hasSellableItems(MinecraftClient client) {
        if (client.player == null) return false;

        int protectedSword = -1, protectedPick = -1, protectedAxe = -1;
        int protectedHelm = -1, protectedChest = -1, protectedLegs = -1, protectedBoots = -1;

        for (int i = 0; i <= 35; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            Item item = s.getItem();

            if (isMaxedSword(client, s) && protectedSword == -1) protectedSword = i;
            if (matchesType(item, "pickaxe") && protectedPick == -1) protectedPick = i;
            if (matchesType(item, "axe") && protectedAxe == -1) protectedAxe = i;

            if (matchesType(item, "helmet") && protectedHelm == -1) protectedHelm = i;
            if (matchesType(item, "chestplate") && protectedChest == -1) protectedChest = i;
            if (matchesType(item, "leggings") && protectedLegs == -1) protectedLegs = i;
            if (matchesType(item, "boots") && protectedBoots == -1) protectedBoots = i;
        }

        int equippedTotems = (client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0)
                + (client.player.getInventory().getStack(8).isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0);
        int maxBackpackTotems = 8;

        int keptTotems = equippedTotems;
        int keptHarmingArrows = 0;
        int keptGapples = 0, keptCobweb = 0, keptPearls = 0, keptXp = 0, keptStrength = 0, keptEchests = 0;

        int keptBows = 0;
        for (int i = 0; i < 9; i++) {
            if (isBowItem(client.player.getInventory().getStack(i))) keptBows++;
        }

        for (int slot = 9; slot <= 35; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            if (slot == protectedSword || slot == protectedPick || slot == protectedAxe
                    || slot == protectedHelm || slot == protectedChest || slot == protectedLegs || slot == protectedBoots) {
                continue;
            }

            if (isMaxedArmor(client, stack)) continue;
            if (isSpawner(stack)) continue;

            if (item == Items.GLASS_BOTTLE || item == Items.OBSIDIAN) return true;

            if (item == Items.ENDER_CHEST) {
                if (keptEchests < 64) {
                    keptEchests += stack.getCount();
                    continue;
                }
                return true;
            }

            if (isBowItem(stack)) {
                if (keptBows < 2) {
                    keptBows++;
                    continue;
                }
                return true;
            }

            if (item == Items.TOTEM_OF_UNDYING) {
                if (keptTotems < maxBackpackTotems) { keptTotems++; continue; }
                return true;
            }

            if (matchesType(item, "helmet") || matchesType(item, "chestplate") || matchesType(item, "leggings") || matchesType(item, "boots")) {
                if (getArmorAhPrice(client, stack) != null) return true;
                continue;
            }
            if (matchesType(item, "sword") || matchesType(item, "pickaxe") || matchesType(item, "axe") || matchesType(item, "shovel") || matchesType(item, "hoe")) {
                if (getToolAhPrice(client, stack) != null) return true;
                continue;
            }

            if (item == Items.ARROW || item == Items.SPECTRAL_ARROW) return true;
            if (item == Items.TIPPED_ARROW) {
                if (!isHarmingArrow(stack)) return true;
                if (keptHarmingArrows < 192) { keptHarmingArrows += stack.getCount(); continue; }
                return true;
            }

            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                if (keptGapples < 64) { keptGapples += stack.getCount(); continue; }
                return true;
            }
            if (item == Items.COBWEB) {
                if (keptCobweb < 64) { keptCobweb += stack.getCount(); continue; }
                return true;
            }
            if (item == Items.ENDER_PEARL) {
                if (keptPearls < 16) { keptPearls += stack.getCount(); continue; }
                return true;
            }
            if (item == Items.EXPERIENCE_BOTTLE) {
                if (keptXp < 64) { keptXp += stack.getCount(); continue; }
                return true;
            }
            if (isStrengthPotion(stack)) {
                if (keptStrength < 4) { keptStrength++; continue; }
            }

            return true;
        }
        return false;
    }

    public static boolean isCombatReady(MinecraftClient client) {
        if (client.player == null) return false;
        boolean hasArmor = !client.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isEmpty();
        boolean hasWeapon = CombatManager.hasSword(client);
        return hasArmor && hasWeapon;
    }

    public static String getMasterName(MinecraftClient client) {
        if (client == null || client.player == null) return "itzNatsuu";
        
        String pName = client.player.getName().getString().toLowerCase();
        String sbName = "";

        if (client.world != null) {
            net.minecraft.scoreboard.Scoreboard scoreboard = client.world.getScoreboard();
            if (scoreboard != null) {
                net.minecraft.scoreboard.ScoreboardObjective obj = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
                if (obj != null) {
                    sbName = Autotpa.cleanText(obj.getDisplayName().getString()).toLowerCase();
                }
            }
        }

        if (pName.contains("llamaposh") || sbName.contains("llamaposh")) {
            return "miles_1dk";
        }

        if (pName.contains("mile") || sbName.contains("mile")) {
            return "obo_pro15";
        }

        return "itzNatsuu";
    }

    private static boolean clickConfirmIfPresent(MinecraftClient client, GenericContainerScreen screen) {
        if (screen == null || client.interactionManager == null || client.player == null) return false;
        int syncId = screen.getScreenHandler().syncId;
        int totalSlots = screen.getScreenHandler().slots.size();

        for (int i = 0; i < Math.min(totalSlots, 54); i++) {
            ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String name = Autotpa.cleanText(stack.getName().getString()).toLowerCase();

            if (name.contains("auction") || name.contains("аукцион") || name.contains("/ah")) continue;

            if (name.contains("стак") || name.contains("stack") || name.contains("64")) {
                client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.PICKUP, client.player);
                Autotpa.sendFeedback("§a[Shop] Выбран стак: " + stack.getName().getString());
                return true;
            }
        }

        for (int i = 0; i < Math.min(totalSlots, 54); i++) {
            ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String name = Autotpa.cleanText(stack.getName().getString()).toLowerCase();

            if (name.contains("auction") || name.contains("аукцион") || name.contains("/ah")) continue;

            if (name.contains("подтверд") || name.contains("confirm") || name.contains("купить") 
                    || name.contains("buy") || name.contains("принять") || name.equals("да") 
                    || name.equals("yes") || name.contains("соглас")) {
                
                client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.PICKUP, client.player);
                Autotpa.sendFeedback("§a[Shop] Подтверждение покупки: " + stack.getName().getString());
                return true;
            }
        }
        return false;
    }

    public static void checkBalanceAndAutoPay(MinecraftClient client) {
        if (payCooldownTicks > 0) {
            payCooldownTicks--;
            return;
        }

        if (client.world == null || client.player == null) return;
        if (CombatManager.isCombatActive(client)) return;

        net.minecraft.scoreboard.Scoreboard scoreboard = client.world.getScoreboard();
        if (scoreboard == null) return;

        net.minecraft.scoreboard.ScoreboardObjective objective = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) return;

        double currentBalance = 0;

        for (net.minecraft.scoreboard.ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
            String owner = entry.owner();
            net.minecraft.scoreboard.Team team = scoreboard.getScoreHolderTeam(owner);
            String line = net.minecraft.scoreboard.Team.decorateName(team, Text.literal(owner)).getString();
            String clean = Autotpa.cleanText(line);

            if (clean.contains("$")) {
                Matcher m = Pattern.compile("(?i)\\$\\s*([0-9.,]+(?:[kmb])?)").matcher(clean);
                if (m.find()) {
                    currentBalance = parsePriceValue(m.group(1));
                    break;
                }
            }
        }

        if (currentBalance >= 30_000_000.0) {
            String recipient = getMasterName(client);
            CommandQueue.send("pay " + recipient + " 20m");
            Autotpa.sendFeedback("§a[AutoPay] Баланс: §e$" + String.format(Locale.US, "%.1fM", currentBalance / 1_000_000.0) + " §a(>=30M)! Отправил §b/pay " + recipient + " 20m");
            payCooldownTicks = 600;
        } else {
            payCooldownTicks = 100;
        }
    }

    private static boolean hasFreeSlot(MinecraftClient client) {
        if (client.player == null) return false;
        for (int i = 0; i <= 35; i++) {
            if (client.player.getInventory().getStack(i).isEmpty()) return true;
        }
        return false;
    }

    public static double getItemPrice(MinecraftClient client, ItemStack stack) {
        String pStr = extractPrice(client, stack);
        return parsePriceValue(pStr);
    }

    public static double parsePriceValue(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) return 0;
        priceStr = priceStr.toLowerCase().replaceAll("[\\s$~]", "");
        double multiplier = 1.0;
        if (priceStr.endsWith("k")) {
            multiplier = 1_000.0;
            priceStr = priceStr.substring(0, priceStr.length() - 1);
        } else if (priceStr.endsWith("m")) {
            multiplier = 1_000_000.0;
            priceStr = priceStr.substring(0, priceStr.length() - 1);
        } else if (priceStr.endsWith("b")) {
            multiplier = 1_000_000_000.0;
            priceStr = priceStr.substring(0, priceStr.length() - 1);
        }
        try {
            return Double.parseDouble(priceStr) * multiplier;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String extractPrice(MinecraftClient client, ItemStack stack) {
        try {
            if (client.player == null || stack == null || stack.isEmpty()) return null;

            net.minecraft.component.type.LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null) {
                for (Text line : lore.lines()) {
                    String cleanLine = Autotpa.cleanText(line.getString());
                    Matcher m = PRICE_PATTERN.matcher(cleanLine);
                    if (m.find()) {
                        String p = m.group(1).replaceAll("[\\s,]", "").toLowerCase();
                        if (!p.isEmpty() && Character.isDigit(p.charAt(0))) {
                            return p;
                        }
                    }
                }
            }

            List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, client.player, TooltipType.Default.ADVANCED);
            for (Text line : tooltip) {
                String cleanLine = Autotpa.cleanText(line.getString());
                Matcher m = PRICE_PATTERN.matcher(cleanLine);
                if (m.find()) {
                    String p = m.group(1).replaceAll("[\\s,]", "").toLowerCase();
                    if (!p.isEmpty() && Character.isDigit(p.charAt(0))) {
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void selectHotbarSlot(MinecraftClient client, int slotIndex) {
        if (client.player == null) return;
        if (client.player.getInventory().getSelectedSlot() != slotIndex) {
            client.player.getInventory().setSelectedSlot(slotIndex);
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slotIndex));
            }
        }
    }

    private static void restoreWeapon(MinecraftClient client, int slot) {
        if (client.player == null || client.interactionManager == null) return;
        client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, slot, 0, SlotActionType.SWAP, client.player);
    }

    public static String buildAhSearchQuery(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        Item item = stack.getItem();
        String name = item.toString().toLowerCase();

        StringBuilder query = new StringBuilder();

        if (name.contains("netherite_chestplate")) query.append("neth chestplate");
        else if (name.contains("netherite_helmet")) query.append("neth helmet");
        else if (name.contains("netherite_leggings")) query.append("neth leggings");
        else if (name.contains("netherite_boots")) query.append("neth boots");
        else if (name.contains("netherite_sword")) query.append("neth sword");
        else if (name.contains("netherite_pickaxe")) query.append("neth pickaxe");
        else if (name.contains("netherite_axe")) query.append("neth axe");
        else if (name.contains("netherite_shovel")) query.append("neth shovel");
        else if (name.contains("netherite_hoe")) query.append("neth hoe");
        else if (item == Items.MACE || name.contains("mace")) query.append("mace");
        else {
            query.append(name.replace("minecraft:", "").replace("_", " "));
        }

        ItemEnchantmentsComponent component = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (RegistryEntry<Enchantment> entry : component.getEnchantments()) {
            String rawKey = entry.getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse(entry.toString().toLowerCase());
            int lvl = component.getLevel(entry);

            String cleanEnchantName = rawKey.replace("_", " ");
            query.append(" ").append(cleanEnchantName);

            if (lvl > 1 || (!cleanEnchantName.equals("mending") && !cleanEnchantName.equals("silk touch") && !cleanEnchantName.equals("aqua affinity"))) {
                query.append(" ").append(lvl);
            }
        }

        return query.toString().trim();
    }

    public static boolean isPower5Bow(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.BOW) return false;

        ItemEnchantmentsComponent component = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (RegistryEntry<Enchantment> entry : component.getEnchantments()) {
            String key = entry.getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse(entry.toString().toLowerCase());
            int lvl = component.getLevel(entry);
            if (key.contains("power") && lvl >= 5) return true;
        }
        return false;
    }

    public static String formatPrice(double price) {
        if (price >= 1_000_000.0) {
            double inM = price / 1_000_000.0;
            if (inM == (long) inM) {
                return String.format(Locale.US, "%dm", (long) inM);
            } else {
                return String.format(Locale.US, "%.1fm", inM);
            }
        } else if (price >= 1_000.0) {
            double inK = price / 1_000.0;
            if (inK == (long) inK) {
                return String.format(Locale.US, "%dk", (long) inK);
            } else {
                return String.format(Locale.US, "%.0fk", inK);
            }
        } else {
            return String.valueOf((long) Math.max(1000, price));
        }
    }

    public static boolean isMaxedArmor(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        boolean isArmor = (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE 
                        || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS
                        || item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE 
                        || item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS);
        if (!isArmor) return false;

        ItemEnchantmentsComponent component = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        boolean hasProt = false;
        boolean hasMending = false;
        boolean hasUnb = false;
        boolean hasThorns = false;

        for (RegistryEntry<Enchantment> entry : component.getEnchantments()) {
            String key = entry.getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse(entry.toString().toLowerCase());
            int lvl = component.getLevel(entry);
            if (key.equals("protection") && lvl >= 4) hasProt = true;
            if (key.contains("mending") && lvl >= 1) hasMending = true;
            if (key.contains("unbreaking") && lvl >= 3) hasUnb = true;
            if (key.contains("thorns") && lvl >= 3) hasThorns = true;
        }

        boolean isTorso = (item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE
                        || item == Items.DIAMOND_LEGGINGS || item == Items.NETHERITE_LEGGINGS);

        if (isTorso) {
            return hasProt && hasMending && hasUnb && hasThorns;
        }

        return hasProt && hasMending && hasUnb;
    }

    public static boolean isEfficiency5Pickaxe(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item != Items.DIAMOND_PICKAXE && item != Items.NETHERITE_PICKAXE) return false;

        ItemEnchantmentsComponent component = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        boolean hasEff5 = false;

        for (RegistryEntry<Enchantment> entry : component.getEnchantments()) {
            String key = entry.getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse(entry.toString().toLowerCase());
            int lvl = component.getLevel(entry);
            if (key.contains("efficiency") && lvl >= 5) hasEff5 = true;
        }

        return hasEff5;
    }

    public static String getArmorAhPrice(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();
        
        boolean isArmor = (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE 
                        || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS
                        || item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE 
                        || item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS);
        if (!isArmor) return null;

        double hoverPrice = getItemPrice(client, stack);
        if (hoverPrice > 0) {
            double priceToUse = Math.max(1000.0, hoverPrice - 1000.0);
            return formatPrice(priceToUse);
        }

        String normKey = getNormalizedEnchantKey(stack);
        if (dynamicShopPrices.containsKey(new NormalizedGearKey(item, normKey))) {
            return dynamicShopPrices.get(new NormalizedGearKey(item, normKey));
        }

        return null;
    }

    public static boolean isMaxedSword(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item != Items.DIAMOND_SWORD && item != Items.NETHERITE_SWORD) return false;

        ItemEnchantmentsComponent component = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        boolean hasSharp = false;
        boolean hasMending = false;
        boolean hasUnb = false;

        for (RegistryEntry<Enchantment> entry : component.getEnchantments()) {
            String key = entry.getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse(entry.toString().toLowerCase());
            int lvl = component.getLevel(entry);
            if (key.contains("sharpness") && lvl >= 5) hasSharp = true;
            if (key.contains("mending") && lvl >= 1) hasMending = true;
            if (key.contains("unbreaking") && lvl >= 3) hasUnb = true;
        }

        return hasSharp && hasMending && hasUnb;
    }

    public static boolean isBowItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() == Items.BOW) return true;
        String name = stack.getName().getString().toLowerCase();
        return (name.contains("лук") || name.contains("bow")) && !name.contains("cross") && !name.contains("арбалет");
    }

    public static boolean hasMaxedSwordInInventory(MinecraftClient client) {
        if (client.player == null) return false;
        for (int i = 0; i <= 35; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (isMaxedSword(client, s)) return true;
        }
        return false;
    }

    public static boolean hasArmorPiece(MinecraftClient client, String type) {
        if (client.player == null) return false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                ItemStack s = client.player.getEquippedStack(slot);
                if (matchesType(s.getItem(), type)) return true;
            }
        }
        for (int i = 0; i <= 35; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (matchesType(s.getItem(), type)) return true;
        }
        return false;
    }

    private static boolean matchesType(Item item, String type) {
        if (item == null) return false;
        String name = item.toString().toLowerCase();
        return switch (type) {
            case "sword" -> name.contains("sword");
            case "axe" -> (name.contains("_axe") || name.endsWith("axe")) && !name.contains("pickaxe"); 
            case "pickaxe" -> name.contains("pickaxe");
            case "shovel" -> name.contains("shovel");
            case "hoe" -> name.contains("hoe");
            case "bow" -> item == Items.BOW || (name.contains("bow") && !name.contains("cross"));
            case "cobweb" -> item == Items.COBWEB;
            case "pearl" -> item == Items.ENDER_PEARL;
            case "obsidian" -> item == Items.OBSIDIAN;
            case "totem" -> item == Items.TOTEM_OF_UNDYING;
            case "mace" -> item == Items.MACE || name.contains("mace");
            case "helmet" -> name.contains("helmet") || name.contains("cap");
            case "chestplate" -> name.contains("chestplate") || name.contains("tunic");
            case "leggings" -> name.contains("leggings") || name.contains("pants");
            case "boots" -> name.contains("boots");
            default -> false;
        };
    }

    private static String getNormalizedEnchantKey(ItemStack stack) {
        ItemEnchantmentsComponent component = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        List<String> list = new ArrayList<>();
        for (RegistryEntry<Enchantment> entry : component.getEnchantments()) {
            String key = entry.getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse(entry.toString().toLowerCase());
            list.add(key + "=" + component.getLevel(entry));
        }
        Collections.sort(list);
        return String.join(";", list);
    }

    public static String getToolAhPrice(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();
        
        boolean isDiamondTool = (item == Items.DIAMOND_SWORD || item == Items.DIAMOND_PICKAXE || item == Items.DIAMOND_AXE);
        if (!isDiamondTool) return null;

        double hoverPrice = getItemPrice(client, stack);
        if (hoverPrice > 0) {
            double priceToUse = Math.max(1000.0, hoverPrice - 1000.0);
            return formatPrice(priceToUse);
        }

        String normKey = getNormalizedEnchantKey(stack);
        if (dynamicShopPrices.containsKey(new NormalizedGearKey(item, normKey))) {
            return dynamicShopPrices.get(new NormalizedGearKey(item, normKey));
        }

        return null;
    }
}