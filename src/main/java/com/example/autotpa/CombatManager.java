package com.example.autotpa;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.mob.EndermiteEntity;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CombatManager {

    public static boolean isRepairingTrap = false;
    public static boolean isVerifyingHome1 = false;
    private static int home1VerifyTimer = 0;
    private static int home1RetryCount = 0;
    private static int home1RetryCooldown = 0;
    private static double home1StartX = 0, home1StartY = 0, home1StartZ = 0;

    private static int lootRushTicks = 0;
    private static int postKillLootTimer = 0;
    private static boolean needsAutoSellAfterKill = false;

    private static AbstractClientPlayerEntity currentEnemy = null;
    private static AbstractClientPlayerEntity lastCombatEnemy = null;
    private static int swordCombatTimerTicks = 0;

    // --- ПЕРЕМЕННЫЕ АИМА И ЛУКА ---
    private static double aimDriftX = 0;
    private static double aimDriftY = 0;
    private static double aimDriftZ = 0;
    private static int aimDriftTimer = 0;
    private static int bowChargeTicks = 0;
    private static int bowShotsFired = 0;
    private static boolean isFinishingWithBow = false;
    private static float lastEnemyAbsorption = 0f;
    private static int arrowShotCheckTimer = 0;
    private static int lastArrowCountBeforeShot = -1;
    private static int consecutiveBowFailures = 0;

    // --- КУЛДАУНЫ И СОСТОЯНИЯ ---
    private static int combatRepairCooldown = 0;
    private static int combatRepairLockTicks = 0;
    private static int mendingCooldown = 0;
    private static int combatMendingCooldown = 0;
    private static int potionBuffCooldown = 0;
    private static int gappleUseTimer = 0;
    private static int gappleCooldown = 0;
    private static boolean isEatingGapple = false;
    private static boolean isDrinkingPotion = false;
    private static int potionDrinkTimer = 0;
    private static int activePotionHotbarSlot = 0;
    private static int potionOriginalInventorySlot = -1;
    public static boolean isMending = false;
    public static boolean isMendingToFull = false;

    // --- ЭНДЕРМИТЫ ---
    private static boolean wasCleaningEndermite = false;
    private static BlockPos endermiteCobwebPos = null;

    // --- ПОБЕГ ---
    private static Vec3d chorusStartPos = null;
    private static Vec3d preHome3Pos = null;
    private static int escapeTpaSendTimer = 0;
    private static Vec3d escapeTpaCheckPos = null;
    private static int chorusInitialCount = 0;
    private static int initialPearlsThrown = 0;
    private static int escapePearlTimer = 0;
    private static int stateTimerTicks = 0;
    private static int reconnectSecondsLeft = 0;

    // --- СТРУКТУРА ЛОВУШКИ: 1 ПАУТИНА В НОГАХ + СТЕНЫ ИЗ ЭНДЕР-СУНДУКОВ (БЕЗ КРЫШИ) ---
    private static final Set<BlockPos> savedWalls = new HashSet<>();
    private static final Set<BlockPos> savedCobweb = new HashSet<>();
    private static File trapFile;
    private static final Random random = new Random();

    // --- ОВЕРЛЕЙ БОЯ ---
    private static final Pattern COMBAT_PATTERN = Pattern.compile("(?iu)(?:бой|combat|pvp|в\\s*бою)[^0-9]*(\\d+)");
    private static String lastParsedString = "";
    private static String lastActionbarText = "Нет данных";
    private static long combatExpiration = 0;
    private static long lastCombatOverlayTime = 0;
    private static int serverCombatSeconds = 0;
    private static java.lang.reflect.Field overlayMessageField = null;
    private static java.lang.reflect.Field overlayRemainingField = null;

    public static void init(File configDir) {
        trapFile = new File(configDir, "trap_structure.txt");
        loadTrap();
    }

    public static BlockPos getTrapFeetPos(MinecraftClient client) {
        if (!savedCobweb.isEmpty()) {
            return savedCobweb.iterator().next();
        }
        return client.player != null ? client.player.getBlockPos() : null;
    }

    public static BlockPos getTrapHeadPos(MinecraftClient client) {
        BlockPos feet = getTrapFeetPos(client);
        return feet != null ? feet.up() : (client.player != null ? client.player.getBlockPos().up() : null);
    }

    public static boolean isAtTrap(MinecraftClient client) {
        if (client.player == null) return false;
        BlockPos feet = getTrapFeetPos(client);
        if (feet == null) return false;
        return client.player.squaredDistanceTo(Vec3d.ofCenter(feet)) <= 4.0;
    }

    public static boolean isTrapReady(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        BlockPos feet = getTrapFeetPos(client);
        BlockPos head = getTrapHeadPos(client);
        if (feet == null || head == null) return false;

        // 1. В ногах строго паутина
        if (!client.world.getBlockState(feet).isOf(Blocks.COBWEB)) return false;

        // 2. На голове НЕТ крыши (чистый воздух)
        if (!client.world.getBlockState(head).isAir()) return false;

        return isAtTrap(client);
    }

    // Совместимость для TpaManager
    public static boolean isTrapReadyWithCobwebs(MinecraftClient client) {
        return isTrapReady(client);
    }

    public static boolean isPostKillProcessing() {
        return postKillLootTimer > 0 || needsAutoSellAfterKill;
    }

    public static void startBotSession(MinecraftClient client) {
        if (client == null || client.player == null) return;
        equipArmorFromInventory(client);
        Autotpa.sendFeedback("§a[MasterBot] Экипировка проверена. Телепортация в /home 1...");
        Autotpa.currentBotState = Autotpa.BotState.STARTING_SETUP_HOME1;
        sendHome1WithVerification(client);
    }

    public static void checkInGameHudOverlay(MinecraftClient client) {
        if (client == null || client.inGameHud == null) return;
        try {
            if (overlayMessageField == null || overlayRemainingField == null) {
                for (java.lang.reflect.Field f : client.inGameHud.getClass().getDeclaredFields()) {
                    if (f.getName().equals("overlayMessage") || f.getName().equals("field_2018")) {
                        f.setAccessible(true);
                        overlayMessageField = f;
                    }
                    if (f.getName().equals("overlayRemaining") || f.getName().equals("field_2019")) {
                        f.setAccessible(true);
                        overlayRemainingField = f;
                    }
                }
            }

            if (overlayRemainingField != null && overlayMessageField != null) {
                int remainingTicks = overlayRemainingField.getInt(client.inGameHud);
                if (remainingTicks <= 0) {
                    serverCombatSeconds = 0;
                    combatExpiration = 0;
                    lastActionbarText = "Нет";
                    return;
                }

                Text t = (Text) overlayMessageField.get(client.inGameHud);
                if (t != null) {
                    String str = t.getString();
                    if (str != null && !str.trim().isEmpty()) {
                        parseCombatOverlay(str, true);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static void parseCombatOverlay(String text, boolean overlay) {
        if (text == null || text.trim().isEmpty()) return;
        String clean = text.replaceAll("(?i)[§&][0-9a-z]", "").trim();
        String lower = clean.toLowerCase();

        if (lower.contains("restart queue") || lower.contains("очередь перезапуска")) {
            Autotpa.sendFeedback("§c[AutoTPA] Сервер уходит на перезапуск! Безопасный выход...");
            MinecraftClient.getInstance().disconnect(Text.literal("Restart Queue"));
            Autotpa.currentBotState = Autotpa.BotState.DISCONNECTED_WAITING;
            reconnectSecondsLeft = 60;
            return;
        }

        if (overlay || lower.contains("бой") || lower.contains("combat") || lower.contains("pvp")) {
            Matcher m = COMBAT_PATTERN.matcher(lower);
            if (m.find()) {
                try {
                    int sec = Integer.parseInt(m.group(1));
                    if (sec > 0 && sec <= 60) {
                        if (!clean.equals(lastParsedString)) {
                            lastActionbarText = clean;
                            lastCombatOverlayTime = System.currentTimeMillis();
                            serverCombatSeconds = sec;
                            lastParsedString = clean;
                            long duration = (sec == 1) ? 1500L : (sec * 1000L + 1200L);
                            combatExpiration = System.currentTimeMillis() + duration;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public static boolean isServerCombatActive() {
        if (serverCombatSeconds <= 0) return false;
        if (lastCombatOverlayTime == 0 || (System.currentTimeMillis() - lastCombatOverlayTime > 2000L)) {
            serverCombatSeconds = 0;
            combatExpiration = 0;
            lastParsedString = "";
            return false;
        }
        return true;
    }

    public static boolean isCombatActive(MinecraftClient client) {
        if (currentEnemy != null && currentEnemy.isAlive() && client.player != null && client.player.distanceTo(currentEnemy) < 10.0) {
            return true;
        }
        return isServerCombatActive();
    }

    public static void setCombatExpiration(long timestamp) {
        combatExpiration = timestamp;
        serverCombatSeconds = (int) Math.max(1, (timestamp - System.currentTimeMillis()) / 1000L);
        lastCombatOverlayTime = System.currentTimeMillis();
    }

    public static String getDebugCombatStatus(MinecraftClient client) {
        checkInGameHudOverlay(client);

        long remainingMs = Math.max(0, combatExpiration - System.currentTimeMillis());
        double remainingSec = remainingMs / 1000.0;
        long timeSinceLastPacket = System.currentTimeMillis() - lastCombatOverlayTime;
        boolean inCombat = isCombatActive(client);

        String enemyInfo = (currentEnemy != null && currentEnemy.isAlive())
                ? currentEnemy.getName().getString() + " (" + String.format("%.1f", client.player.distanceTo(currentEnemy)) + "m)"
                : "Нет";

        return "§6[DEBUG-PVP] §fВ бою: " + (inCombat ? "§cДА" : "§aНЕТ") +
               " §f| Оверлей: §e" + String.format("%.1f", remainingSec) + "с (Сервер: " + (inCombat ? serverCombatSeconds : 0) + "с)" +
               " §f| Пакет: §b" + (lastCombatOverlayTime == 0 ? "Никогда" : timeSinceLastPacket + "мс назад") +
               " §f| Экшенбар: '§e" + lastActionbarText + "§f'" +
               " §f| Враг: §b" + enemyInfo;
    }
    // =========================================================================
    // БЕЗОПАСНЫЙ СНОС ВРЕМЕННОЙ ПАУТИНЫ (СВЕРХУ ВНИЗ) ПОСЛЕ СУШКИ ЛАВЫ/ВОДЫ
    // Паутину в ногах НЕ ТРОГАЕМ НИКОГДА!
    // =========================================================================
    public static boolean handleCleanupTemporaryCobwebs(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        BlockPos feetPos = getTrapFeetPos(client);
        if (feetPos == null) return false;

        // Проверяем блоки над ногами: от Y+3 вниз до уровня головы Y+1
        for (int y = 3; y >= 1; y--) {
            BlockPos checkPos = feetPos.up(y);
            BlockState state = client.world.getBlockState(checkPos);

            if (state.isOf(Blocks.COBWEB)) {
                BlockPos above = checkPos.up();
                
                // ЗАЩИТА: если прямо над этой паутиной всё еще осталась вода или лава —
                // пока НЕ ломаем, чтобы жидкость не хлынула обратно в лицо!
                if (hasFluid(client, above) || hasFluid(client, checkPos)) {
                    continue; 
                }

                // Жидкость высушена! Безопасно срубаем временную паутину мечом
                ensureSwordInHand(client);
                if (BlockInteractionHelper.breakBlockLegit(client, checkPos)) {
                    return true; // Ломаем блок до конца, соблюдая все ванильные задержки
                }
            }
        }
        return false;
    }
    // =========================================================================
    // ГЛАВНЫЙ ТИК БОЕВОГО МЕНЕДЖЕРА
    // =========================================================================
    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        // 1. ЭКСТРЕННАЯ СУШКА ЛАВЫ И ВОДЫ СВЕРХУ ПАУТИНОЙ (снизу вверх)
        if (handleTrapFluidDefense(client)) {
            return;
        }

        // 2. БЕЗОПАСНЫЙ СНОС ПАУТИНЫ МЕЧОМ (сверху вниз, только когда жидкость исчезла)
        if (handleCleanupTemporaryCobwebs(client)) {
            return;
        }

        checkInGameHudOverlay(client);
        if (!Autotpa.isMasterBotEnabled) return;

        checkHome1Verification(client);

        if (Autotpa.currentBotState == Autotpa.BotState.ESCAPE_EATING_CHORUS
                || Autotpa.currentBotState == Autotpa.BotState.ESCAPE_RUNNING) {
            if (!checkCancelEscapeIfEnemyInTrap(client)) {
                if (Autotpa.currentBotState == Autotpa.BotState.ESCAPE_EATING_CHORUS) {
                    handleChorusEating(client);
                    return;
                }
                if (Autotpa.currentBotState == Autotpa.BotState.ESCAPE_RUNNING) {
                    handleEscapeRunning(client);
                    return;
                }
            }
        }

        switch (Autotpa.currentBotState) {
            case STARTING_SETUP_HOME1 -> { return; }
            case ESCAPE_HOME3_WAIT -> { handleHome3AndLeave(client); return; }
            case RECONNECTED_SETUP -> { handleReconnectedSetup(client); return; }
            case TRAP_BUILDING -> { return; }
        }

        ensureTotem(client);

        // Поиск источника воды/лавы в ловушке
        BlockPos fluidSource = findFluidSourceInTrap(client);
        if (fluidSource != null) {
            BlockInteractionHelper.stopBreaking(client);
            if (BlockInteractionHelper.placeBlockLegit(client, fluidSource, Items.COBWEB)) {
                Autotpa.sendFeedback("§b[Trap] Вода/лава высушена паутиной!");
                return;
            }
        }

        // БЕЗ КРЫШИ: Если кто-то поставил блок на уровне головы — сносим!
        BlockPos headPos = getTrapHeadPos(client);
        if (headPos != null) {
            BlockState headState = client.world.getBlockState(headPos);
            if (!headState.isAir() && headState.getFluidState().isEmpty() && !headState.isOf(Blocks.BEDROCK)) {
                if (BlockInteractionHelper.breakBlockLegit(client, headPos)) {
                    return;
                }
            }
        }

        AbstractClientPlayerEntity foundEnemy = findTarget(client);
        currentEnemy = foundEnemy;

        if (currentEnemy != null) {
            if (AutoSellManager.currentState != AutoSellManager.SellState.IDLE || client.currentScreen != null) {
                Autotpa.sendFeedback("§4§l[ТРЕВОГА] ВРАГ В ЛОВУШКЕ! ОТМЕНА ПРОДАЖИ, В БОЙ!");
                AutoSellManager.abortAutoSell(client);
                ensureSwordInHand(client);
            }
        }

        // Экстренный побег, если мы голые
        if (currentEnemy != null && !AutoSellManager.isCombatReady(client)) {
            Autotpa.sendFeedback("§4§l[ВНИМАНИЕ] ВРАГ РЯДОМ, А МЫ БЕЗ ОРУЖИЯ! ПОБЕГ!");
            startEscape(client);
            return;
        }

        // Зелья и яблоки
        if (handleDrinkingPotionProcess(client)) return;
        if (handleGappleEatingProcess(client)) {
            if (currentEnemy != null && currentEnemy.isAlive()) {
                aimAt(client, getDynamicBodyAimPos(currentEnemy));
                client.options.forwardKey.setPressed(true);
            }
            return;
        }

        if (!client.player.hasStatusEffect(StatusEffects.STRENGTH)) {
            if (applyStrengthPotion(client)) return;
        }

        if (!isDrinkingPotion) {
            checkAndEatGoldenApple(client);
            if (isEatingGapple) return;
        }

        // =========================================================================
        // 1. БОЙ С ПРОТИВНИКОМ В 1x1
        // =========================================================================
        if (currentEnemy != null) {
            isRepairingTrap = false;
            BlockInteractionHelper.stopBreaking(client);

            // Починка стен и 1 паутины в ногах прямо во время боя
            if (handleCombatTrapRepair(client)) return;

            // Проверка смены противника
            if (currentEnemy != lastCombatEnemy) {
                lastCombatEnemy = currentEnemy;
                swordCombatTimerTicks = 0;
                bowShotsFired = 0;
                isFinishingWithBow = false;
                lastEnemyAbsorption = currentEnemy.getAbsorptionAmount();
            }

            // --- ТРИГГЕРЫ ПЕРЕКЛЮЧЕНИЯ НА ЛУК ---
            float currentAbsorption = currentEnemy.getAbsorptionAmount();
            boolean poppedTotem = currentAbsorption > (lastEnemyAbsorption + 2.0f);
            boolean hasResistance = currentEnemy.hasStatusEffect(StatusEffects.RESISTANCE);
            boolean enemyHoldingBow = isHoldingBow(currentEnemy);

            // Если лопнул тотем, съел гэппл, взял лук или бой мечом длится >30 сек -> ЛУК!
            if (poppedTotem || hasResistance || enemyHoldingBow || swordCombatTimerTicks >= 600) {
                if (!isFinishingWithBow) {
                    isFinishingWithBow = true;
                    bowShotsFired = 0;
                    consecutiveBowFailures = 0;
                    if (poppedTotem) Autotpa.sendFeedback("§6[TrapBot] Враг лопнул тотем! Переключаюсь на лук!");
                    else if (hasResistance) Autotpa.sendFeedback("§e[TrapBot] Эффект Сопротивления у врага! Добиваю луком!");
                    else if (enemyHoldingBow) Autotpa.sendFeedback("§c[TrapBot] Враг достал лук! Достаю лук в ответ!");
                    else Autotpa.sendFeedback("§e[TrapBot] Долгий бой мечом! Перехожу на лук!");
                }
            }
            lastEnemyAbsorption = currentAbsorption;

            // Проверка щита врага
            boolean isEnemyShielding = currentEnemy.isBlocking() || (currentEnemy.isUsingItem() &&
                    (currentEnemy.getMainHandStack().isOf(Items.SHIELD) || currentEnemy.getOffHandStack().isOf(Items.SHIELD)));

            // Если враг поднял щит -> СБИВАЕМ ТОПОРОМ!
            if (isEnemyShielding) {
                client.options.useKey.setPressed(false);
                bowChargeTicks = 0;
                ensureAxeInHand(client);
                aimAt(client, getDynamicBodyAimPos(currentEnemy));
                client.options.forwardKey.setPressed(true);

                if (client.player.getAttackCooldownProgress(0.0f) >= 0.90f) {
                    client.interactionManager.attackEntity(client.player, currentEnemy);
                    client.player.swingHand(Hand.MAIN_HAND);
                    Autotpa.sendFeedback("§6[TrapBot] Щит врага сбит топором!");
                }
                return;
            }

            // --- РЕЖИМ ЛУКА ---
            if (isFinishingWithBow) {
                int arrowCount = countItem(client, Items.ARROW) + countItem(client, Items.TIPPED_ARROW) + countItem(client, Items.SPECTRAL_ARROW);
                int bowCount = countItem(client, Items.BOW);

                if (arrowCount <= 0 || bowCount <= 0) {
                    isFinishingWithBow = false;
                    Autotpa.sendFeedback("§c[TrapBot] Стрелы/луки закончились! Возвращаюсь к мечу!");
                    ensureSwordInHand(client);
                } else {
                    if (!AutoSellManager.isBowItem(client.player.getInventory().getStack(6))) {
                        AutoSellManager.ensureBowInSlot6(client);
                    }

                    selectHotbarSlot(client, 6);
                    bowChargeTicks++;

                    updateBowSpacing(client, currentEnemy);
                    Vec3d ballisticAim = calculateBowAimPoint(client, currentEnemy, Math.max(bowChargeTicks, 4));
                    aimAt(client, ballisticAim);

                    if (bowChargeTicks == 1 || !client.player.isUsingItem()) {
                        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                    }
                    client.options.useKey.setPressed(true);

                    int requiredTicks = (bowShotsFired < 2) ? 7 : 4;
                    if (consecutiveBowFailures >= 1) requiredTicks = 8;

                    if (bowChargeTicks >= requiredTicks) {
                        client.options.useKey.setPressed(false);
                        bowChargeTicks = 0;
                        bowShotsFired++;
                        lastArrowCountBeforeShot = arrowCount;
                        arrowShotCheckTimer = 8;
                    }
                    return;
                }
            }

            // --- РЕЖИМ МЕЧА ---
            ensureSwordInHand(client);
            client.options.useKey.setPressed(false);
            bowChargeTicks = 0;

            aimAt(client, getDynamicBodyAimPos(currentEnemy));
            client.options.forwardKey.setPressed(true);

            swordCombatTimerTicks++;

            float progress = client.player.getAttackCooldownProgress(0.0f);
            boolean readyToAttack = (progress >= 1.0f);

            if (readyToAttack && client.player.distanceTo(currentEnemy) <= 3.5) {
                client.interactionManager.attackEntity(client.player, currentEnemy);
                client.player.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        // =========================================================================
        // 2. ВРАГ УНИЧТОЖЕН (СМЕРТЬ ВРАГА)
        // =========================================================================
        if (currentEnemy == null && lastCombatEnemy != null) {
            Autotpa.sendFeedback("§a[TrapBot] Враг уничтожен!");
            lastCombatEnemy = null;
            isFinishingWithBow = false;
            swordCombatTimerTicks = 0;
            bowChargeTicks = 0;
            bowShotsFired = 0;
            serverCombatSeconds = 0;
            combatExpiration = 0;
            releaseControls(client);
            ensureSwordInHand(client);

            if (hasGroundLootInTrap(client)) {
                postKillLootTimer = 40;
                needsAutoSellAfterKill = true;
            } else {
                postKillLootTimer = 0;
                needsAutoSellAfterKill = false;
                Autotpa.sendFeedback("§a[TrapBot] Лута нет, сразу готов к следующему игроку!");
            }
        }

        // =========================================================================
        // 3. ПОДБОР ЛУТА ПОСЛЕ КИЛЛА
        // =========================================================================
        if (postKillLootTimer > 0) {
            postKillLootTimer--;
            handleGroundLootRush(client);

            if (!hasGroundLootInTrap(client) || postKillLootTimer == 0) {
                postKillLootTimer = 0;
                boolean shouldSell = needsAutoSellAfterKill && AutoSellManager.hasSellableItems(client);
                needsAutoSellAfterKill = false;

                if (shouldSell && AutoSellManager.currentState == AutoSellManager.SellState.IDLE) {
                    Autotpa.sendFeedback("§6[TrapBot] Весь лут собран! Продажа (/sell)...");
                    AutoSellManager.forceStartAutoSell(client);
                    return;
                }
            }
            return;
        }

        // =========================================================================
        // 4. РЕЖИМ ОЖИДАНИЯ В 1x1 (ВНЕ БОЯ)
        // =========================================================================
        if (currentEnemy == null) {
            if (handleEndermiteProtocol(client)) return;

            if (handleTrapRepair(client)) {
                isRepairingTrap = true;
                return;
            }
            isRepairingTrap = false;

            if (handleMending(client)) return;

            if (getEmptySlotCount(client) > 1 && handleGroundLootRush(client)) {
                return;
            }
        }
    }

    private static boolean isHoldingBow(AbstractClientPlayerEntity player) {
        if (player == null) return false;
        Item main = player.getMainHandStack().getItem();
        Item off = player.getOffHandStack().getItem();
        return main == Items.BOW || main == Items.CROSSBOW || off == Items.BOW || off == Items.CROSSBOW;
    }

    // --- ПОЧИНКА ЛОВУШКИ ВНЕ БОЯ (1 ПАУТИНА В НОГАХ + ЭНДЕР-СУНДУКИ) ---
    public static boolean handleTrapRepair(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;

        // 1. Паутина в ногах (строго одна)
        BlockPos feetPos = getTrapFeetPos(client);
        if (feetPos != null) {
            BlockState feetState = client.world.getBlockState(feetPos);
            if (!feetState.isOf(Blocks.COBWEB) && !feetState.isOf(Blocks.BEDROCK)) {
                if (isFluidOrReplaceable(feetState)) {
                    BlockInteractionHelper.stopBreaking(client);
                    if (BlockInteractionHelper.placeBlockLegit(client, feetPos, Items.COBWEB)) return true;
                } else {
                    if (BlockInteractionHelper.breakBlockLegit(client, feetPos)) return true;
                }
            }
        }

        // 2. Стены из эндер-сундуков
        for (BlockPos pos : savedWalls) {
            if (client.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 25.0) continue;

            BlockState state = client.world.getBlockState(pos);
            if (state.isOf(Blocks.ENDER_CHEST) || state.isOf(Blocks.BEDROCK)) continue;

            if (isFluidOrReplaceable(state)) {
                BlockInteractionHelper.stopBreaking(client);
                if (BlockInteractionHelper.placeBlockLegit(client, pos, Items.ENDER_CHEST)) {
                    return true;
                }
            } else {
                BlockInteractionHelper.breakBlockLegit(client, pos);
                return true;
            }
        }

        BlockInteractionHelper.stopBreaking(client);
        return false;
    }

    // --- ПОЧИНКА В БОЮ ---
    private static boolean handleCombatTrapRepair(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;

        if (combatRepairLockTicks > 0) {
            combatRepairLockTicks--;
            return true;
        }

        // 1. Паутина в ногах
        BlockPos feetPos = getTrapFeetPos(client);
        if (feetPos != null) {
            BlockState state = client.world.getBlockState(feetPos);
            if (!state.isOf(Blocks.COBWEB) && isFluidOrReplaceable(state)) {
                if (BlockInteractionHelper.placeBlockLegit(client, feetPos, Items.COBWEB)) {
                    combatRepairLockTicks = 3;
                    combatRepairCooldown = 5;
                    return true;
                }
            }
        }

        // 2. Стены из эндер-сундуков
        for (BlockPos pos : savedWalls) {
            if (client.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 25.0) continue;
            BlockState state = client.world.getBlockState(pos);
            if (state.isOf(Blocks.ENDER_CHEST) || state.isOf(Blocks.BEDROCK)) continue;

            if (isFluidOrReplaceable(state)) {
                if (BlockInteractionHelper.placeBlockLegit(client, pos, Items.ENDER_CHEST)) {
                    combatRepairCooldown = 5;
                    return true;
                }
            }
        }

        return false;
    }

    private static void updateBowSpacing(MinecraftClient client, AbstractClientPlayerEntity enemy) {
        if (client.player == null || enemy == null || client.options == null) return;
        BlockPos feetPos = getTrapFeetPos(client);
        if (feetPos == null) return;

        double targetX = (enemy.getX() > feetPos.getX() + 0.5) ? (feetPos.getX() + 0.22) : (feetPos.getX() + 0.78);
        double targetZ = (enemy.getZ() > feetPos.getZ() + 0.5) ? (feetPos.getZ() + 0.22) : (feetPos.getZ() + 0.78);

        double dx = targetX - client.player.getX();
        double dz = targetZ - client.player.getZ();
        double distToCorner = Math.sqrt(dx * dx + dz * dz);

        if (distToCorner < 0.05) {
            releaseMovementKeys(client);
            return;
        }

        float moveYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float delta = (float) MathHelper.wrapDegrees(moveYaw - client.player.getYaw());

        client.options.forwardKey.setPressed(delta > -67.5f && delta < 67.5f);
        client.options.backKey.setPressed(delta > 112.5f || delta < -112.5f);
        client.options.leftKey.setPressed(delta < -22.5f && delta > -157.5f);
        client.options.rightKey.setPressed(delta > 22.5f && delta < 157.5f);
    }

    private static void releaseMovementKeys(MinecraftClient client) {
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }
    }

    public static void sendHome1WithVerification(MinecraftClient client) {
        if (client.player == null) return;

        if (isAtTrap(client)) {
            isVerifyingHome1 = false;
            home1VerifyTimer = 0;
            Autotpa.sendFeedback("§a[AutoTPA] Бот уже в ловушке. Готов к ловле!");
            Autotpa.currentBotState = Autotpa.BotState.IDLE_TRAPPING;
            return;
        }

        home1StartX = client.player.getX();
        home1StartY = client.player.getY();
        home1StartZ = client.player.getZ();
        isVerifyingHome1 = true;
        home1VerifyTimer = 40;
        home1RetryCount = 0;
        CommandQueue.send("home 1");
    }

    private static void checkHome1Verification(MinecraftClient client) {
        if (!isVerifyingHome1 || client.player == null) return;

        if (isAtTrap(client)) {
            isVerifyingHome1 = false;
            home1VerifyTimer = 0;
            home1RetryCount = 0;
            Autotpa.sendFeedback("§a[AutoTPA] Успешно прибыл в ловушку!");
            Autotpa.currentBotState = Autotpa.BotState.IDLE_TRAPPING;
            return;
        }

        if (home1VerifyTimer > 0) {
            home1VerifyTimer--;
        } else {
            home1RetryCount++;
            if (home1RetryCount >= 1 || isAtTrap(client)) {
                isVerifyingHome1 = false;
                home1VerifyTimer = 0;
                home1RetryCount = 0;
                Autotpa.currentBotState = Autotpa.BotState.IDLE_TRAPPING;
                Autotpa.sendFeedback("§a[AutoTPA] Ловушка готова. Ловля начата!");
                return;
            }
            sendHome1WithVerification(client);
        }
    }

    public static void onGameMessage(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("обслуживании") || lower.contains("maintenance") || lower.contains("недоступна")) {
            if (isVerifyingHome1) {
                Autotpa.sendFeedback("§c[AutoTPA] Область на обслуживании. Жду 2 минуты...");
                home1RetryCooldown = 2400;
                home1VerifyTimer = 0;
            }
        }
    }

    private static boolean isFluidOrReplaceable(BlockState state) {
        if (state == null) return false;
        return state.isAir() || state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA) 
                || !state.getFluidState().isEmpty() || state.isReplaceable();
    }

    public static void handleAutoReconnect(MinecraftClient client) {
        Autotpa.isMasterBotEnabled = true;

        if (client.world != null && client.player != null) {
            Autotpa.currentBotState = Autotpa.BotState.RECONNECTED_SETUP;
            stateTimerTicks = 40;
            return;
        }

        if (client.currentScreen instanceof DisconnectedScreen discScreen) {
            for (Element element : discScreen.children()) {
                if (element instanceof ButtonWidget btn) {
                    btn.onPress(null);
                    break;
                }
            }
        }

        if (reconnectSecondsLeft <= 0) {
            reconnectSecondsLeft = 60;
            stateTimerTicks = 20;
        }

        if (stateTimerTicks > 0) {
            stateTimerTicks--;
        } else {
            stateTimerTicks = 20;
            reconnectSecondsLeft--;

            if (reconnectSecondsLeft % 15 == 0 || reconnectSecondsLeft <= 5) {
                Autotpa.sendFeedback("§e[Reconnect] Переподключение к donutsmp.net через " + reconnectSecondsLeft + "с...");
            }

            if (reconnectSecondsLeft <= 0) {
                Autotpa.sendFeedback("§a[Reconnect] 1 минута прошла! Подключаюсь к donutsmp.net...");
                ServerInfo donutInfo = new ServerInfo("DonutSMP", "donutsmp.net", ServerInfo.ServerType.OTHER);
                ConnectScreen.connect(new TitleScreen(), client, ServerAddress.parse("donutsmp.net"), donutInfo, false, null);

                reconnectSecondsLeft = 60;
                stateTimerTicks = 200;
                Autotpa.currentBotState = Autotpa.BotState.RECONNECTED_SETUP;
            }
        }
    }

    private static boolean ensureAxeInHand(MinecraftClient client) {
        if (client.player == null) return false;
        ItemStack slot2 = client.player.getInventory().getStack(2);
        String s2Name = slot2.getItem().toString().toLowerCase();
        if ((s2Name.contains("_axe") || s2Name.endsWith("axe")) && !s2Name.contains("pickaxe")) {
            selectHotbarSlot(client, 2);
            return true;
        }

        for (int i = 0; i < 9; i++) {
            String name = client.player.getInventory().getStack(i).getItem().toString().toLowerCase();
            if ((name.contains("_axe") || name.endsWith("axe")) && !name.contains("pickaxe")) {
                selectHotbarSlot(client, i);
                return true;
            }
        }

        for (int i = 9; i <= 35; i++) {
            String name = client.player.getInventory().getStack(i).getItem().toString().toLowerCase();
            if ((name.contains("_axe") || name.endsWith("axe")) && !name.contains("pickaxe")) {
                int syncId = client.player.playerScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, i, 2, SlotActionType.SWAP, client.player);
                selectHotbarSlot(client, 2);
                return true;
            }
        }
        return false;
    }

    private static void handleReconnectedSetup(MinecraftClient client) {
        if (client.player == null) return;
        if (stateTimerTicks > 0) {
            stateTimerTicks--;
            releaseControls(client);
        } else {
            releaseControls(client);
            resetSession(client);
            Autotpa.sendFeedback("§a[AutoTPA] Перезаход успешен! Возвращаюсь в /home 1...");
            sendHome1WithVerification(client);
            serverCombatSeconds = 0;
            combatExpiration = 0;
        }
    }

    public static boolean handleMending(MinecraftClient client) {
        if (mendingCooldown > 0) mendingCooldown--;

        if (currentEnemy != null || AutoSellManager.currentState != AutoSellManager.SellState.IDLE) {
            if (isMending) {
                client.options.useKey.setPressed(false);
                isMending = false;
                isMendingToFull = false;
            }
            return false;
        }

        if (isArmorNeedsMending(client)) {
            isMendingToFull = true;
        }

        if (isMendingToFull) {
            if (!isArmorNotFullyRepaired(client)) {
                isMendingToFull = false;
                isMending = false;
                client.options.useKey.setPressed(false);
                ensureSwordInHand(client);
                Autotpa.sendFeedback("§a[Mending] Вся броня восстановлена до 100%!");
                return false;
            }

            isMending = true;
            int xpSlot = -1;

            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) {
                    xpSlot = i;
                    break;
                }
            }

            if (xpSlot == -1) {
                for (int i = 9; i <= 35; i++) {
                    if (client.player.getInventory().getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) {
                        int syncId = client.player.playerScreenHandler.syncId;
                        client.interactionManager.clickSlot(syncId, i, 2, SlotActionType.SWAP, client.player);
                        xpSlot = 2;
                        break;
                    }
                }
            }

            if (xpSlot != -1) {
                client.player.getInventory().setSelectedSlot(xpSlot);
                client.player.setPitch(89.0f);
                client.options.useKey.setPressed(true);
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                client.player.swingHand(Hand.MAIN_HAND);
                return true;
            } else {
                client.options.useKey.setPressed(false);
                isMending = false;
                isMendingToFull = false;
                if (mendingCooldown <= 0) {
                    Autotpa.sendFeedback("§c[Mending] Нет опыта! Запускаю авто-закупку...");
                    AutoSellManager.startAutoSell(client);
                    mendingCooldown = 100;
                }
                return false;
            }
        }
        return false;
    }

    public static boolean isItemInsideTrap(MinecraftClient client, ItemEntity itemEntity) {
        if (client.world == null || client.player == null || itemEntity == null || !itemEntity.isAlive()) return false;
        double dist = client.player.distanceTo(itemEntity);
        if (dist > 2.5) return false;

        BlockPos feet = getTrapFeetPos(client);
        if (feet == null) return false;

        Vec3d itemPos = new Vec3d(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ());
        double dx = itemPos.x - (feet.getX() + 0.5);
        double dz = itemPos.z - (feet.getZ() + 0.5);
        double hDist = Math.sqrt(dx * dx + dz * dz);

        return hDist <= 1.2 && itemPos.y >= feet.getY() - 0.5 && itemPos.y <= feet.getY() + 2.5;
    }

    public static boolean hasGroundLootInTrap(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return false;
        for (ItemEntity entity : client.world.getEntitiesByClass(ItemEntity.class, client.player.getBoundingBox().expand(3.0), e -> e.isAlive())) {
            if (isItemInsideTrap(client, entity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean handleGroundLootRush(MinecraftClient client) {
        if (currentEnemy != null || AutoSellManager.currentState != AutoSellManager.SellState.IDLE) {
            lootRushTicks = 0;
            return false;
        }
        if (client.world == null || client.player == null) return false;

        for (ItemEntity entity : client.world.getEntitiesByClass(ItemEntity.class, client.player.getBoundingBox().expand(2.5), e -> e.isAlive())) {
            if (!isItemInsideTrap(client, entity)) continue;

            Item item = entity.getStack().getItem();
            String name = item.toString().toLowerCase();

            boolean isValuable = name.contains("helmet") || name.contains("chestplate") 
                    || name.contains("leggings") || name.contains("boots") 
                    || name.contains("sword") || name.contains("axe") 
                    || name.contains("pickaxe") || item == Items.MACE || item == Items.TOTEM_OF_UNDYING;

            if (isValuable) {
                lootRushTicks++;
                client.options.forwardKey.setPressed(true);

                if (lootRushTicks > 30) {
                    client.options.forwardKey.setPressed(false);
                    lootRushTicks = 0;
                    return false;
                }
                return true;
            }
        }

        if (lootRushTicks > 0) {
            client.options.forwardKey.setPressed(false);
            lootRushTicks = 0;
        }
        return false;
    }

    public static void startEscape(MinecraftClient client) {
        currentEnemy = null; lastCombatEnemy = null;
        swordCombatTimerTicks = 0; bowShotsFired = 0;

        Autotpa.currentBotState = Autotpa.BotState.ESCAPE_EATING_CHORUS;
        stateTimerTicks = 45;
        chorusStartPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        preHome3Pos = null;
        initialPearlsThrown = 0;
        escapePearlTimer = 0;
        escapeTpaSendTimer = 0;
        escapeTpaCheckPos = null;

        releaseControls(client);

        int chorusSlot = ensureChorusInHotbar(client);
        chorusInitialCount = countItem(client, Items.CHORUS_FRUIT);

        if (chorusSlot != -1) {
            client.player.getInventory().setSelectedSlot(chorusSlot);
            client.options.useKey.setPressed(true);
        } else {
            client.options.useKey.setPressed(false);
            Autotpa.currentBotState = Autotpa.BotState.ESCAPE_RUNNING;
        }
    }

    private static void handleChorusEating(MinecraftClient client) {
        if (client.player == null) return;
        client.options.sneakKey.setPressed(true);
        ensureTotem(client);

        int currentChorus = countItem(client, Items.CHORUS_FRUIT);
        boolean hasTeleported = chorusStartPos != null && client.player.squaredDistanceTo(chorusStartPos) > 16.0;
        boolean hasEaten = currentChorus < chorusInitialCount;

        if (hasTeleported || hasEaten || stateTimerTicks <= 0) {
            client.options.useKey.setPressed(false);
            ensureSwordInHand(client);
            Autotpa.currentBotState = Autotpa.BotState.ESCAPE_RUNNING;
            restoreGappleToSlot4(client);
            initialPearlsThrown = 0;
            escapePearlTimer = 0;
            Autotpa.sendFeedback("§b[Escape] Хорус съеден! 2 броска перла...");
        } else {
            stateTimerTicks--;
            client.options.useKey.setPressed(true);
        }
    }

    private static void handleEscapeRunning(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        client.options.sneakKey.setPressed(true);
        ensureTotem(client);

        String master = AutoSellManager.getMasterName(client);

        if (escapeTpaCheckPos != null && client.player.squaredDistanceTo(escapeTpaCheckPos) > 1000000.0) {
            Autotpa.sendFeedback("§a[Escape] Успешный ТП к " + master + "! Выход на 1 мин...");
            escapeTpaCheckPos = null;
            escapeTpaSendTimer = 0;
            Autotpa.currentBotState = Autotpa.BotState.DISCONNECTED_WAITING;
            client.disconnect(Text.literal("Safe Exit (TPA Escape)"));
            reconnectSecondsLeft = 60;
            stateTimerTicks = 20;
            return;
        }

        boolean inWater = client.player.isTouchingWater() || client.player.isSubmergedInWater()
                || client.world.getBlockState(client.player.getBlockPos()).isOf(Blocks.WATER);

        if (inWater) {
            client.options.sneakKey.setPressed(false); 
            client.options.jumpKey.setPressed(true);
            client.options.forwardKey.setPressed(true);
            client.player.setPitch(-89.0f);
        } else {
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.sneakKey.setPressed(true);
        }

        if (escapeTpaSendTimer > 0) {
            escapeTpaSendTimer--;
        } else {
            escapeTpaCheckPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
            CommandQueue.send("tpa " + master);
            escapeTpaSendTimer = 20;
        }

        boolean enemyNearby = false;
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p != client.player && p.isAlive() && !p.isSpectator() && !FriendManager.isPlayerFriend(p)) {
                if (client.player.distanceTo(p) <= 10.0) {
                    enemyNearby = true;
                    break;
                }
            }
        }

        if (escapePearlTimer > 0) {
            escapePearlTimer--;
        } else {
            boolean canThrow = !client.player.getItemCooldownManager().isCoolingDown(new ItemStack(Items.ENDER_PEARL));
            if (canThrow) {
                if (initialPearlsThrown < 2) {
                    if (throwEnderPearl(client)) {
                        initialPearlsThrown++;
                        escapePearlTimer = 35; 
                    }
                } else if (enemyNearby) {
                    if (throwEnderPearl(client)) {
                        escapePearlTimer = 35;
                    }
                }
            }
        }
    }

    private static void handleHome3AndLeave(MinecraftClient client) {
        if (client.player == null) return;
        if (stateTimerTicks > 0) {
            stateTimerTicks--;
            if (preHome3Pos != null && client.player.squaredDistanceTo(preHome3Pos) > 225.0) {
                Autotpa.currentBotState = Autotpa.BotState.DISCONNECTED_WAITING;
                client.disconnect(Text.literal("Safe Exit (/home 3)"));
                reconnectSecondsLeft = 60;
                stateTimerTicks = 20;
            }
        } else {
            setCombatExpiration(System.currentTimeMillis() + 10000L);
            initialPearlsThrown = 0; 
            Autotpa.currentBotState = Autotpa.BotState.ESCAPE_RUNNING;
        }
    }

    public static AbstractClientPlayerEntity findTarget(MinecraftClient client) {
        if (client.world == null || client.player == null) return null;
        AbstractClientPlayerEntity target = null;
        double min = 10.0;
        String master = AutoSellManager.getMasterName(client);

        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || !p.isAlive() || p.isSpectator()) continue;
            String pName = p.getName().getString();
            if (pName.equalsIgnoreCase(master) || pName.equalsIgnoreCase("itzNatsuu") || pName.equalsIgnoreCase("obo_pro15")) continue;
            if (FriendManager.isPlayerFriend(p)) continue;

            double d = client.player.distanceTo(p);
            if (d <= 10.0 && d < min) {
                min = d;
                target = p;
            }
        }
        return target;
    }

    private static boolean handleEndermiteProtocol(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        if (currentEnemy != null || isCombatActive(client)) {
            wasCleaningEndermite = false;
            endermiteCobwebPos = null;
            return false;
        }

        EndermiteEntity endermite = null;
        double minDist = 4.5;

        for (EndermiteEntity e : client.world.getEntitiesByClass(EndermiteEntity.class, client.player.getBoundingBox().expand(4.0), e -> e.isAlive())) {
            double d = client.player.distanceTo(e);
            if (d < minDist) {
                minDist = d;
                endermite = e;
            }
        }

        if (endermite != null) {
            wasCleaningEndermite = true;
            BlockPos feetPos = getTrapFeetPos(client);

            if (feetPos != null && client.world.getBlockState(feetPos).isOf(Blocks.COBWEB)) {
                endermiteCobwebPos = feetPos;
                BlockInteractionHelper.breakBlockLegit(client, feetPos);
                return true;
            }

            ensureSwordInHand(client);
            aimAt(client, new Vec3d(endermite.getX(), endermite.getY() + 0.15, endermite.getZ()));

            if (client.player.getAttackCooldownProgress(0.0f) >= 0.90f) {
                client.interactionManager.attackEntity(client.player, endermite);
                client.player.swingHand(Hand.MAIN_HAND);
            }
            return true;
        }

        if (wasCleaningEndermite) {
            BlockPos targetWeb = (endermiteCobwebPos != null) ? endermiteCobwebPos : getTrapFeetPos(client);
            if (targetWeb != null && (client.world.getBlockState(targetWeb).isAir() || client.world.getBlockState(targetWeb).isReplaceable())) {
                BlockInteractionHelper.placeBlockLegit(client, targetWeb, Items.COBWEB);
            }
            wasCleaningEndermite = false;
            endermiteCobwebPos = null;
        }
        return false;
    }

    private static boolean aimAt(MinecraftClient client, Vec3d target) {
        if (client.player == null || client.options == null) return false;

        Vec3d eye = client.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        if (hDist < 0.15) return true;

        float targetYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) MathHelper.wrapDegrees(-Math.toDegrees(Math.atan2(dy, hDist)));

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;
        float angleDist = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

        float speed = Math.min(32.0f, Math.max(6.0f, angleDist * 0.45f)) + (random.nextFloat() * 2.0f - 1.0f);
        float stepYaw = MathHelper.clamp(deltaYaw, -speed, speed);
        float stepPitch = MathHelper.clamp(deltaPitch, -speed, speed);

        double sensitivity = client.options.getMouseSensitivity().getValue();
        double f = sensitivity * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;

        stepYaw = (float) (Math.round(stepYaw / gcd) * gcd);
        stepPitch = (float) (Math.round(stepPitch / gcd) * gcd);

        client.player.setYaw(currentYaw + stepYaw);
        client.player.setPitch(MathHelper.clamp(currentPitch + stepPitch, -90.0f, 90.0f));

        return Math.abs(deltaYaw) < 15.0f && Math.abs(deltaPitch) < 15.0f;
    }

    private static void ensureTotem(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        if (client.player.getOffHandStack().getItem() != Items.TOTEM_OF_UNDYING) {
            for (int i = 9; i <= 35; i++) {
                if (client.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                    int id = client.player.playerScreenHandler.syncId;
                    client.interactionManager.clickSlot(id, i, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(id, 45, 0, SlotActionType.PICKUP, client.player);
                    break;
                }
            }
        }
    }

    public static boolean equipArmorFromInventory(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return false;

        boolean equippedAny = false;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

        for (EquipmentSlot slot : slots) {
            if (client.player.getEquippedStack(slot).isEmpty()) {
                for (int i = 0; i <= 35; i++) {
                    ItemStack stack = client.player.getInventory().getStack(i);
                    if (stack.isEmpty()) continue;

                    if (isArmorForSlot(stack, slot)) {
                        int syncId = client.player.playerScreenHandler.syncId;
                        int containerSlot = (i < 9) ? (36 + i) : i;
                        client.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.QUICK_MOVE, client.player);
                        equippedAny = true;
                        break;
                    }
                }
            }
        }
        return equippedAny;
    }

    private static boolean isArmorForSlot(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty()) return false;
        String name = stack.getItem().toString().toLowerCase();
        switch (slot) {
            case HEAD: return name.contains("helmet") || name.contains("cap");
            case CHEST: return name.contains("chestplate") || name.contains("tunic");
            case LEGS: return name.contains("leggings") || name.contains("pants");
            case FEET: return name.contains("boots");
            default: return false;
        }
    }

    public static boolean isFullyArmored(MinecraftClient client) {
        if (client.player == null) return false;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            if (client.player.getEquippedStack(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean handleGappleEatingProcess(MinecraftClient client) {
        if (isEatingGapple) {
            gappleUseTimer++;
            selectHotbarSlot(client, 4);
            client.options.useKey.setPressed(true);

            boolean finishedNaturally = (gappleUseTimer > 10 && !client.player.isUsingItem());
            if (gappleUseTimer >= 42 || finishedNaturally) {
                client.options.useKey.setPressed(false);
                isEatingGapple = false;
                gappleUseTimer = 0;
                gappleCooldown = 40; 
                selectHotbarSlot(client, 0);
                if (!isFinishingWithBow) ensureSwordInHand(client);
            }
            return true;
        }
        return false;
    }

    private static void checkAndEatGoldenApple(MinecraftClient client) {
        if (client.player == null || isEatingGapple || isDrinkingPotion || isFinishingWithBow) return;
        restoreGappleToSlot4(client);
        if (gappleCooldown > 0) {
            gappleCooldown--;
            return;
        }

        float health = client.player.getHealth();
        int food = client.player.getHungerManager().getFoodLevel();

        if (health <= 14.0f || food <= 12) {
            ItemStack slot4Stack = client.player.getInventory().getStack(4);
            if (slot4Stack.getItem() == Items.GOLDEN_APPLE || slot4Stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
                isEatingGapple = true;
                gappleUseTimer = 0;
                selectHotbarSlot(client, 4);
                client.options.useKey.setPressed(true);
            }
        }
    }

    public static boolean isArmorNeedsMending(MinecraftClient client) {
        if (client.player == null) return false;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack stack = client.player.getEquippedStack(slot);
            if (!stack.isEmpty() && stack.isDamageable() && stack.getDamage() > (stack.getMaxDamage() / 2)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isArmorNotFullyRepaired(MinecraftClient client) {
        if (client.player == null) return false;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack stack = client.player.getEquippedStack(slot);
            if (!stack.isEmpty() && stack.isDamageable() && stack.getDamage() > 0) {
                return true;
            }
        }
        return false;
    }

    public static final int SLOT_SWORD = 0;
    public static final int SLOT_COBWEB = 1;
    public static final int SLOT_AXE = 2;
    public static final int SLOT_PEARL = 3;
    public static final int SLOT_GAPPLE = 4;
    public static final int SLOT_WALL = 5;
    public static final int SLOT_BOW = 6;
    public static final int SLOT_PICKAXE = 7;
    public static final int SLOT_POTION = 8;

    private static boolean applyStrengthPotion(MinecraftClient client) {
        if (potionBuffCooldown > 0 || isDrinkingPotion || isFinishingWithBow) {
            if (potionBuffCooldown > 0) potionBuffCooldown--;
            return false;
        }

        if (client.player.hasStatusEffect(StatusEffects.STRENGTH)) return false;
        if (findFluidSourceInTrap(client) != null) return false;

        for (int i = 0; i <= 35; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;

            if (isStrengthPotion(client, s)) {
                if (isEatingGapple) {
                    isEatingGapple = false;
                    gappleUseTimer = 0;
                    client.options.useKey.setPressed(false);
                }

                if (s.getItem() == Items.SPLASH_POTION) {
                    int prevSlot = client.player.getInventory().getSelectedSlot();
                    if (i < 9) {
                        selectHotbarSlot(client, i);
                        client.player.setPitch(89.0f);
                        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                        client.player.swingHand(Hand.MAIN_HAND);
                        selectHotbarSlot(client, prevSlot);
                    } else {
                        int syncId = client.player.playerScreenHandler.syncId;
                        client.interactionManager.clickSlot(syncId, i, SLOT_POTION, SlotActionType.SWAP, client.player);
                        selectHotbarSlot(client, SLOT_POTION);
                        client.player.setPitch(89.0f);
                        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                        client.player.swingHand(Hand.MAIN_HAND);
                        client.interactionManager.clickSlot(syncId, i, SLOT_POTION, SlotActionType.SWAP, client.player);
                        selectHotbarSlot(client, prevSlot);
                    }
                    ensureSwordInHand(client);
                    potionBuffCooldown = 400;
                    return true;
                }

                if (i == SLOT_POTION) {
                    activePotionHotbarSlot = SLOT_POTION;
                    selectHotbarSlot(client, SLOT_POTION);
                    potionOriginalInventorySlot = -1;
                } else if (i < 9) {
                    activePotionHotbarSlot = i;
                    selectHotbarSlot(client, i);
                    potionOriginalInventorySlot = -1;
                } else {
                    int syncId = client.player.playerScreenHandler.syncId;
                    client.interactionManager.clickSlot(syncId, i, SLOT_POTION, SlotActionType.SWAP, client.player);
                    activePotionHotbarSlot = SLOT_POTION;
                    selectHotbarSlot(client, SLOT_POTION);
                    potionOriginalInventorySlot = i;
                }

                client.player.setPitch(-89.0f);
                isDrinkingPotion = true;
                potionDrinkTimer = 0;
                client.options.useKey.setPressed(true);
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                return true;
            }
        }
        potionBuffCooldown = 40;
        return false;
    }
    // =========================================================================
    // АВТО-СУШКА ВОДЫ И ЛАВЫ СВЕРХУ: СТАВИМ ПАУТИНУ НА ПАУТИНУ
    // =========================================================================
    public static boolean handleTrapFluidDefense(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        BlockPos feetPos = getTrapFeetPos(client);
        if (feetPos == null) return false;

        BlockPos headPos = feetPos.up();
        BlockPos aboveHeadPos = headPos.up();

        boolean headHasFluid = hasFluid(client, headPos);
        boolean aboveHasFluid = hasFluid(client, aboveHeadPos);

        if (!headHasFluid && !aboveHasFluid) {
            return false;
        }

        // 1. Если жидкость на уровне головы — ставим паутину на паутину в ногах:
        if (headHasFluid || (aboveHasFluid && client.world.getBlockState(headPos).isAir())) {
            if (BlockInteractionHelper.placeCobwebOnTopOf(client, feetPos)) {
                Autotpa.sendFeedback("§c[Trap] Лава/вода обнаружена! Сушу паутиной на голове...");
                return true;
            }
        }

        // 2. Если жидкость выше головы, а на голове уже есть паутина — ставим паутину на паутину головы:
        if (aboveHasFluid && client.world.getBlockState(headPos).isOf(Blocks.COBWEB)) {
            if (BlockInteractionHelper.placeCobwebOnTopOf(client, headPos)) {
                Autotpa.sendFeedback("§c[Trap] Сушу источник лавы/воды над головой!");
                return true;
            }
        }

        return false;
    }

    private static boolean hasFluid(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA) || !state.getFluidState().isEmpty();
    }
    private static boolean handleDrinkingPotionProcess(MinecraftClient client) {
        if (isDrinkingPotion) {
            potionDrinkTimer++;
            selectHotbarSlot(client, activePotionHotbarSlot);
            client.player.setPitch(-89.0f);
            client.options.useKey.setPressed(true);

            if (potionDrinkTimer == 1 || (potionDrinkTimer > 3 && !client.player.isUsingItem())) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }

            boolean hasStrength = client.player.hasStatusEffect(StatusEffects.STRENGTH);
            boolean bottleAppeared = client.player.getInventory().getStack(activePotionHotbarSlot).isOf(Items.GLASS_BOTTLE);

            if (potionDrinkTimer >= 36 || (potionDrinkTimer >= 24 && (hasStrength || bottleAppeared))) {
                client.options.useKey.setPressed(false);
                isDrinkingPotion = false;
                potionDrinkTimer = 0;
                potionBuffCooldown = 400;

                if (potionOriginalInventorySlot != -1) {
                    int syncId = client.player.playerScreenHandler.syncId;
                    client.interactionManager.clickSlot(syncId, potionOriginalInventorySlot, SLOT_POTION, SlotActionType.SWAP, client.player);
                    potionOriginalInventorySlot = -1;
                }

                selectHotbarSlot(client, SLOT_SWORD);
                ensureSwordInHand(client);
            }
            return true;
        }
        return false;
    }

    public static void restoreGappleToSlot4(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        ItemStack slot4Stack = client.player.getInventory().getStack(4);
        if (slot4Stack.isOf(Items.GOLDEN_APPLE) || slot4Stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) return;

        int syncId = client.player.playerScreenHandler.syncId;
        for (int i = 0; i <= 35; i++) {
            if (i == 4) continue;
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
                int containerSlot = (i < 9) ? (36 + i) : i;
                client.interactionManager.clickSlot(syncId, containerSlot, 4, SlotActionType.SWAP, client.player);
                return;
            }
        }
    }

    private static boolean isStrengthPotion(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item != Items.POTION && item != Items.SPLASH_POTION) return false;

        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents != null) {
            if (contents.potion().isPresent()) {
                String id = contents.potion().get().getKey().map(k -> k.getValue().getPath().toLowerCase()).orElse("");
                if (id.contains("strength")) return true;
            }
            for (net.minecraft.entity.effect.StatusEffectInstance effect : contents.customEffects()) {
                String effectId = effect.getEffectType().getIdAsString().toLowerCase();
                if (effectId.contains("strength")) return true;
            }
        }
        String name = Autotpa.cleanText(stack.getName().getString()).toLowerCase();
        return name.contains("сил") || name.contains("strength");
    }

    private static Vec3d getDynamicBodyAimPos(AbstractClientPlayerEntity enemy) {
        float currentHeight = enemy.getHeight();
        aimDriftTimer--;
        if (aimDriftTimer <= 0) {
            aimDriftX = (random.nextDouble() - 0.5) * 0.25;
            aimDriftY = (random.nextDouble() - 0.5) * 0.20;
            aimDriftZ = (random.nextDouble() - 0.5) * 0.25;
            aimDriftTimer = 8 + random.nextInt(8);
        }
        double targetOffsetY = Math.max(0.25, currentHeight * 0.5) + aimDriftY;
        return new Vec3d(enemy.getX() + aimDriftX, enemy.getY() + targetOffsetY, enemy.getZ() + aimDriftZ);
    }

    private static Vec3d calculateBowAimPoint(MinecraftClient client, AbstractClientPlayerEntity enemy, int chargeTicks) {
        Vec3d enemyCenter = getDynamicBodyAimPos(enemy);
        Vec3d playerEyes = client.player.getEyePos();

        double dx = enemyCenter.x - playerEyes.x;
        double dz = enemyCenter.z - playerEyes.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float pull = Math.min((float) chargeTicks / 20.0f, 1.0f);
        float velocity = pull * 3.0f;
        if (velocity < 0.35f) velocity = 0.35f;

        double timeInAir = hDist / velocity;
        double drop = 0.5 * 0.05 * (timeInAir * timeInAir);

        return enemyCenter.add(0, drop, 0);
    }

    private static BlockPos findFluidSourceInTrap(MinecraftClient client) {
        if (client.world == null || client.player == null) return null;
        BlockPos playerPos = client.player.getBlockPos();
        for (int y = 0; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);
                    net.minecraft.fluid.FluidState fluid = client.world.getFluidState(checkPos);
                    if (!fluid.isEmpty() && fluid.isStill()) return checkPos;
                }
            }
        }
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

    public static void releaseControls(MinecraftClient client) {
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.useKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.attackKey.setPressed(false);
        }
    }

    private static int ensureChorusInHotbar(MinecraftClient client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.CHORUS_FRUIT)) return i;
        }
        for (int i = 9; i <= 35; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.CHORUS_FRUIT)) {
                int syncId = client.player.playerScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, i, 4, SlotActionType.SWAP, client.player);
                return 4;
            }
        }
        return -1;
    }

    private static int ensurePearlsInHotbar(MinecraftClient client) {
        if (client.player == null) return -1;
        if (client.player.getInventory().getStack(3).isOf(Items.ENDER_PEARL)) return 3;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) return i;
        }
        for (int i = 9; i <= 35; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) {
                int syncId = client.player.playerScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, i, 3, SlotActionType.SWAP, client.player);
                return 3;
            }
        }
        return -1;
    }

    private static boolean throwEnderPearl(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return false;
        int pearlSlot = ensurePearlsInHotbar(client);
        if (pearlSlot == -1) return false;

        client.player.getInventory().setSelectedSlot(pearlSlot);
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));

        float randomYaw = random.nextFloat() * 360.0f;
        float randomPitch = -(46.0f + random.nextFloat() * 14.0f);
        client.player.setYaw(randomYaw);
        client.player.setPitch(randomPitch);

        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    // Сделан PUBLIC для AutoSellManager
    public static boolean ensureSwordInHand(MinecraftClient client) {
        if (client.player == null) return false;
        ItemStack inHand = client.player.getMainHandStack();
        if (AutoSellManager.isMaxedSword(client, inHand)) return true;

        ItemStack slot0 = client.player.getInventory().getStack(0);
        if (AutoSellManager.isMaxedSword(client, slot0)) {
            selectHotbarSlot(client, 0);
            return true;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (AutoSellManager.isMaxedSword(client, s)) {
                selectHotbarSlot(client, i);
                return true;
            }
        }

        for (int i = 9; i <= 35; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (AutoSellManager.isMaxedSword(client, s)) {
                int syncId = client.player.playerScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.SWAP, client.player);
                selectHotbarSlot(client, 0);
                return true;
            }
        }

        if (inHand.getItem().toString().toLowerCase().contains("sword")) return true;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).getItem().toString().toLowerCase().contains("sword")) {
                selectHotbarSlot(client, i);
                return true;
            }
        }
        for (int i = 9; i <= 35; i++) {
            if (client.player.getInventory().getStack(i).getItem().toString().toLowerCase().contains("sword")) {
                int syncId = client.player.playerScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.SWAP, client.player);
                selectHotbarSlot(client, 0);
                return true;
            }
        }
        return false;
    }

    public static boolean isPlayerInsideTrap(AbstractClientPlayerEntity player) {
        if (player == null || savedCobweb.isEmpty()) return false;
        for (BlockPos webPos : savedCobweb) {
            double dx = player.getX() - (webPos.getX() + 0.5);
            double dz = player.getZ() - (webPos.getZ() + 0.5);
            double hDist = Math.sqrt(dx * dx + dz * dz);
            double dy = player.getY() - webPos.getY();
            if (hDist <= 1.25 && dy >= -0.5 && dy <= 2.5) return true;
        }
        return false;
    }

    private static boolean checkCancelEscapeIfEnemyInTrap(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        AbstractClientPlayerEntity enemyInside = null;
        boolean enemyOutside = false;

        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || !p.isAlive() || p.isSpectator()) continue;
            if (FriendManager.isPlayerFriend(p)) continue;

            double dist = client.player.distanceTo(p);
            if (dist > 15.0) continue;

            if (isPlayerInsideTrap(p)) enemyInside = p;
            else enemyOutside = true;
        }

        if (enemyInside != null && !enemyOutside) {
            Autotpa.sendFeedback("§a§l[Escape Override] Враг в ловушке! Отмена побега -> В БОЙ!");
            Autotpa.currentBotState = Autotpa.BotState.IDLE_TRAPPING;
            client.options.useKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            restoreGappleToSlot4(client);
            ensureSwordInHand(client);
            stateTimerTicks = 0;
            escapeTpaSendTimer = 0;
            escapeTpaCheckPos = null;
            return true;
        }
        return false;
    }

    public static boolean hasSword(MinecraftClient client) {
        if (client.player == null) return false;
        for (int i = 0; i <= 35; i++) {
            if (client.player.getInventory().getStack(i).getItem().toString().toLowerCase().contains("sword")) return true;
        }
        return false;
    }

    public static int countItem(MinecraftClient client, Item item) {
        if (client.player == null) return 0;
        int total = 0;
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    public static int getEmptySlotCount(MinecraftClient client) {
        if (client == null || client.player == null) return 36;
        int empty = 0;
        for (int i = 9; i <= 35; i++) {
            if (client.player.getInventory().getStack(i).isEmpty()) empty++;
        }
        return empty;
    }

    public static void saveTrap(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        BlockPos p = client.player.getBlockPos();
        savedWalls.clear();
        savedCobweb.clear();

        // 1 блок паутины строго под ногами игрока
        savedCobweb.add(p);

        // Сканируем окружающие стены 3x3 на высоту 2 блоков (уровень ног и уровень головы)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                for (int y = 0; y <= 1; y++) {
                    BlockPos target = p.add(x, y, z);
                    BlockState s = client.world.getBlockState(target);
                    if (s.isOf(Blocks.ENDER_CHEST) || s.isOf(Blocks.OBSIDIAN)) {
                        savedWalls.add(target);
                    }
                }
            }
        }
        saveTrapToFile();
        Autotpa.sendFeedback("§a[TrapBot] Ловушка 1x1 сохранена (Паутина: 1, Стены: " + savedWalls.size() + ", Без крыши)");
    }

    private static void saveTrapToFile() {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(trapFile), StandardCharsets.UTF_8))) {
            for (BlockPos pos : savedWalls) { w.write("wall;" + pos.getX() + ";" + pos.getY() + ";" + pos.getZ()); w.newLine(); }
            for (BlockPos pos : savedCobweb) { w.write("cobweb;" + pos.getX() + ";" + pos.getY() + ";" + pos.getZ()); w.newLine(); }
        } catch (Exception ignored) {}
    }

    private static void loadTrap() {
        if (!trapFile.exists()) return;
        savedWalls.clear();
        savedCobweb.clear();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(trapFile), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split(";");
                if (p.length >= 4) {
                    BlockPos pos = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    if (p[0].equals("wall") || p[0].equals("obsidian") || p[0].equals("ender_chest")) savedWalls.add(pos);
                    if (p[0].equals("cobweb")) savedCobweb.add(pos);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void resetSession(MinecraftClient client) {
        currentEnemy = null;
        lastCombatEnemy = null;
        swordCombatTimerTicks = 0;
        bowShotsFired = 0;
        isFinishingWithBow = false;
        isEatingGapple = false;
        isDrinkingPotion = false;
        combatRepairLockTicks = 0;
        isMending = false;
        isRepairingTrap = false;
        isVerifyingHome1 = false;
        activePotionHotbarSlot = 0;
        wasCleaningEndermite = false;
        endermiteCobwebPos = null;
        serverCombatSeconds = 0;
        combatExpiration = 0;
        postKillLootTimer = 0;
        needsAutoSellAfterKill = false;
        BlockInteractionHelper.stopBreaking(client);
        releaseControls(client);
    }
}