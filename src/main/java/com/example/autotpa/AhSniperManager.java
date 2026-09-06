package com.example.autotpa;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class AhSniperManager {

    public static boolean isSniperEnabled = false;

    private enum SniperState {
        IDLE, NEXT_QUERY, WAIT_MENU, ANALYZE_PAGE,
        BUY_ITEM, WAIT_CONFIRM, CLICK_CONFIRM, WAIT_REFRESH,
        FIND_NEXT_SELL_ITEM, PRICE_CHECK_SEND, PRICE_CHECK_WAIT, PRICE_CHECK_READ,
        SELL_ITEM_DIRECTLY, WAIT_SELL_DELAY
    }

    private static SniperState state = SniperState.IDLE;
    private static int delayTicks = 0;

    // Список базовых фильтров для монополизации
    private static final String[] SNIPER_QUERIES = {
            "neth helmet protection 4 unbreaking 3 mending",
            "neth chestplate protection 4 unbreaking 3 mending",
            "neth leggings protection 4 unbreaking 3 mending",
            "neth boots protection 4 unbreaking 3 mending",
            "neth leggings blast protection 4 unbreaking 3 mending",
            "neth boots blast protection 4 unbreaking 3 mending"
    };

    private static int queryIndex = 0;
    
    // Переменные для перепродажи
    private static double currentBuyPrice = 0.0;
    private static double targetSellPrice = 0.0;
    private static int itemsToBuy = 0;

    // Переменные для проверки экстра-чаров
    private static int currentSellSlot = -1;
    private static double dynamicSellPrice = 0.0;
    private static String currentSpecificQuery = "";

    public static void startSniper() {
        isSniperEnabled = true;
        state = SniperState.NEXT_QUERY;
        queryIndex = 0;
        delayTicks = 0;
        Autotpa.sendFeedback("§6[AH Снайпер] §eАлгоритм монополизации рынка запущен!");
    }

    public static void stopSniper() {
        isSniperEnabled = false;
        state = SniperState.IDLE;
        Autotpa.sendFeedback("§c[AH Снайпер] Остановлен.");
    }

    public static void tick(MinecraftClient client) {
        if (!isSniperEnabled || client.player == null || client.world == null) return;

        // --- 1. АНТИ-ПАЛЕВО (РАДАР 50 БЛОКОВ) ---
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            String name = player.getName().getString();
            
            if (name.equalsIgnoreCase("G0tou") || name.equalsIgnoreCase("itzNatsuu")) continue;

            if (client.player.distanceTo(player) <= 50.0) {
                Autotpa.sendFeedback("§4§l[ТРЕВОГА] §cИгрок §e" + name + " §cближе 50 блоков! Экстренный /rtp...");
                if (client.currentScreen != null) client.player.closeHandledScreen();
                CommandQueue.send("rtp");
                
                state = SniperState.NEXT_QUERY;
                delayTicks = 300; // Пауза 15 сек
                return;
            }
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        switch (state) {
            case IDLE -> { }

            case NEXT_QUERY -> {
                if (client.currentScreen != null) client.player.closeHandledScreen();
                
                String query = SNIPER_QUERIES[queryIndex];
                CommandQueue.send("ah " + query);
                
                queryIndex++;
                if (queryIndex >= SNIPER_QUERIES.length) queryIndex = 0;

                state = SniperState.WAIT_MENU;
                delayTicks = 15;
            }

            case WAIT_MENU -> {
                if (client.currentScreen instanceof GenericContainerScreen) {
                    state = SniperState.ANALYZE_PAGE;
                    delayTicks = 5;
                } else {
                    state = SniperState.NEXT_QUERY;
                    delayTicks = 20;
                }
            }

            case ANALYZE_PAGE -> {
                if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
                    state = SniperState.NEXT_QUERY;
                    return;
                }

                int slotsCount = screen.getScreenHandler().slots.size();
                double priceA = 0; 
                int countA = 0;    
                double priceB = 0; 

                for (int i = 0; i < Math.min(slotsCount, 45); i++) {
                    ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
                    if (stack.isEmpty()) continue;

                    double price = AutoSellManager.getItemPrice(client, stack);
                    if (price <= 0) continue;

                    if (priceA == 0) {
                        priceA = price; 
                        countA = 1;
                    } else if (price == priceA) {
                        countA++; 
                    } else if (price > priceA) {
                        priceB = price; 
                        break;
                    }
                }

                if (priceA > 0 && priceB > priceA && countA <= 6) {
                    currentBuyPrice = priceA;
                    targetSellPrice = priceB;
                    itemsToBuy = countA;
                    
                    Autotpa.sendFeedback("§a[AH Снайпер] Найдено " + countA + " лотов по §e" + AutoSellManager.formatPrice(priceA) + "§a. Следующая: §b" + AutoSellManager.formatPrice(priceB) + "§a. ВЫКУПАЕМ!");
                    state = SniperState.BUY_ITEM;
                    delayTicks = 5;
                } else {
                    client.player.closeHandledScreen();
                    state = SniperState.NEXT_QUERY;
                    delayTicks = 20;
                }
            }

            case BUY_ITEM -> {
                if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
                    state = SniperState.FIND_NEXT_SELL_ITEM;
                    return;
                }

                if (itemsToBuy <= 0) {
                    client.player.closeHandledScreen();
                    state = SniperState.FIND_NEXT_SELL_ITEM;
                    delayTicks = 10;
                    return;
                }

                ItemStack slot0 = screen.getScreenHandler().getSlot(0).getStack();
                if (slot0.isEmpty()) {
                    client.player.closeHandledScreen();
                    state = SniperState.FIND_NEXT_SELL_ITEM;
                    return;
                }

                double currentSlotPrice = AutoSellManager.getItemPrice(client, slot0);
                if (currentSlotPrice == currentBuyPrice) {
                    client.interactionManager.clickSlot(screen.getScreenHandler().syncId, 0, 0, SlotActionType.PICKUP, client.player);
                    state = SniperState.WAIT_CONFIRM;
                    delayTicks = 5;
                } else {
                    Autotpa.sendFeedback("§c[AH Снайпер] Цена изменилась! Отмена покупки.");
                    client.player.closeHandledScreen();
                    state = SniperState.FIND_NEXT_SELL_ITEM;
                    delayTicks = 10;
                }
            }

            case WAIT_CONFIRM -> {
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    String title = Autotpa.cleanText(screen.getTitle().getString()).toLowerCase();
                    if (title.contains("confirm") || title.contains("подтверд") || title.contains("buy") || title.contains("купить")) {
                        state = SniperState.CLICK_CONFIRM;
                        delayTicks = 3;
                    } else {
                        state = SniperState.WAIT_REFRESH;
                        delayTicks = 10;
                    }
                }
            }

            case CLICK_CONFIRM -> {
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
                        ItemStack s = screen.getScreenHandler().getSlot(i).getStack();
                        if (s.getItem() == Items.GREEN_STAINED_GLASS_PANE || s.getItem() == Items.GREEN_WOOL || s.getItem() == Items.GREEN_TERRACOTTA) {
                            client.interactionManager.clickSlot(screen.getScreenHandler().syncId, i, 0, SlotActionType.PICKUP, client.player);
                            break;
                        }
                    }
                    itemsToBuy--;
                    state = SniperState.WAIT_REFRESH;
                    delayTicks = 15;
                }
            }

            case WAIT_REFRESH -> {
                if (client.currentScreen instanceof GenericContainerScreen) {
                    state = SniperState.BUY_ITEM;
                    delayTicks = 5;
                } else {
                    state = SniperState.FIND_NEXT_SELL_ITEM;
                    delayTicks = 10;
                }
            }

            // --- ПОИСК КУПЛЕННЫХ ВЕЩЕЙ И ПРОВЕРКА ЭКСТРА-ЧАРОВ ---
            case FIND_NEXT_SELL_ITEM -> {
                if (client.currentScreen != null) client.player.closeHandledScreen();
                currentSellSlot = -1;

                // Ищем незеритку в рюкзаке
                for (int i = 9; i <= 35; i++) {
                    ItemStack s = client.player.getInventory().getStack(i);
                    if (s.isEmpty()) continue;
                    String name = s.getItem().toString().toLowerCase();
                    if (name.contains("netherite_helmet") || name.contains("netherite_chestplate") 
                            || name.contains("netherite_leggings") || name.contains("netherite_boots")) {
                        currentSellSlot = i;
                        break;
                    }
                }

                if (currentSellSlot == -1) {
                    // Всё продали, идем к следующему поиску
                    state = SniperState.NEXT_QUERY;
                    delayTicks = 30;
                    return;
                }

                ItemStack stackToSell = client.player.getInventory().getStack(currentSellSlot);
                
                // Проверяем, есть ли God-Enchants
                if (hasExtraMaxEnchants(stackToSell)) {
                    currentSpecificQuery = AutoSellManager.buildAhSearchQuery(stackToSell);
                    state = SniperState.PRICE_CHECK_SEND;
                    delayTicks = 5;
                } else {
                    dynamicSellPrice = targetSellPrice;
                    state = SniperState.SELL_ITEM_DIRECTLY;
                    delayTicks = 5;
                }
            }

            // --- ПРОВЕРКА ЦЕНЫ ДЛЯ GOD-TIER ВЕЩЕЙ ---
            case PRICE_CHECK_SEND -> {
                CommandQueue.send("ah " + currentSpecificQuery);
                Autotpa.sendFeedback("§d[AH Снайпер] Обнаружены Топ-Чары! Проверка рынка: §e/ah " + currentSpecificQuery);
                state = SniperState.PRICE_CHECK_WAIT;
                delayTicks = 15;
            }

            case PRICE_CHECK_WAIT -> {
                if (client.currentScreen instanceof GenericContainerScreen) {
                    state = SniperState.PRICE_CHECK_READ;
                    delayTicks = 5;
                } else {
                    dynamicSellPrice = targetSellPrice;
                    state = SniperState.SELL_ITEM_DIRECTLY;
                    delayTicks = 5;
                }
            }

            case PRICE_CHECK_READ -> {
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    ItemStack slot0 = screen.getScreenHandler().getSlot(0).getStack();
                    double lowestPrice = 0;
                    if (!slot0.isEmpty()) {
                        lowestPrice = AutoSellManager.getItemPrice(client, slot0);
                    }

                    if (lowestPrice > 0) {
                        // Перебиваем цену на 100k, но не падаем ниже оригинальной цены перепродажи!
                        dynamicSellPrice = Math.max(targetSellPrice, lowestPrice - 100_000.0);
                        Autotpa.sendFeedback("§a[AH Снайпер] Конкурент найден: §e" + AutoSellManager.formatPrice(lowestPrice) + "§a. Продаем за §b" + AutoSellManager.formatPrice(dynamicSellPrice));
                    } else {
                        // Эксклюзив (на аукционе таких нет) -> накидываем +2 миллиона сверху!
                        dynamicSellPrice = targetSellPrice + 2_000_000.0;
                        Autotpa.sendFeedback("§d[AH Снайпер] ЭКСКЛЮЗИВНЫЕ ЧАРЫ! Конкурентов нет, продаем за §b" + AutoSellManager.formatPrice(dynamicSellPrice));
                    }

                    client.player.closeHandledScreen();
                    state = SniperState.SELL_ITEM_DIRECTLY;
                    delayTicks = 10;
                } else {
                    state = SniperState.SELL_ITEM_DIRECTLY;
                }
            }

            // --- ВЫСТАВЛЕНИЕ ПРЕДМЕТА НА ПРОДАЖУ ---
            case SELL_ITEM_DIRECTLY -> {
                int syncId = client.player.playerScreenHandler.syncId;
                // Берем вещь в руку
                client.interactionManager.clickSlot(syncId, currentSellSlot, 0, SlotActionType.SWAP, client.player);
                
                CommandQueue.send("ah sell " + AutoSellManager.formatPrice(dynamicSellPrice));
                Autotpa.sendFeedback("§e[AH Снайпер] Перепродажа: лот выставлен за §b" + AutoSellManager.formatPrice(dynamicSellPrice) + "!");
                
                state = SniperState.WAIT_SELL_DELAY;
                delayTicks = 15;
            }

            case WAIT_SELL_DELAY -> {
                // Убираем вещь обратно в инвентарь (или возвращаем меч на место)
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, currentSellSlot, 0, SlotActionType.SWAP, client.player);
                
                state = SniperState.FIND_NEXT_SELL_ITEM;
                delayTicks = 10;
            }
        }
    }

    // --- ПРОВЕРКА НА GOD ENCHANTS ---
    private static boolean hasExtraMaxEnchants(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String query = AutoSellManager.buildAhSearchQuery(stack).toLowerCase();
        
        return query.contains("thorns 3") || 
               query.contains("swift sneak 3") || 
               query.contains("soul speed 3") || 
               query.contains("depth strider 3") || 
               query.contains("respiration 3");
    }
}