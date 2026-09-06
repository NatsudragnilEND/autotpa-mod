package com.example.autotpa;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TpaManager {

    private static final Deque<String> tpaTargetsQueue = new ArrayDeque<>();
    private static final Map<String, PlayerData> playerHistory = new HashMap<>();
    private static final long SIX_HOURS_MS = 6L * 60 * 60 * 1000;
    private static File storageFile;

    private static int cooldownTicks = 0;
    private static int confirmDelayTicks = -1;
    private static int noVictimTimerTicks = 1200; 
    private static double lastTpaX = 0, lastTpaY = 0, lastTpaZ = 0;

    public static boolean isWaitingItzNatsuu = false;
    private enum NatsuuState {
        IDLE, WAITING_TP, DELAY_BEFORE_HOME
    }
    private static NatsuuState natsuuState = NatsuuState.IDLE;
    private static int natsuuTimeoutTicks = 0;
    private static int natsuuDelayTicks = 0;

    private static String lastTargetPlayer = null;
    private static long lastTargetTime = 0;

    private static final Pattern COMBAT_PATTERN = Pattern.compile("(?i)(?:бой|combat|pvp|в\\s*бою):?\\s*(\\d+)");
    private static final Random random = new Random();

    private static class PlayerData {
        int count;
        long timestamp;
        boolean blacklisted;
        PlayerData(int count, long timestamp, boolean blacklisted) {
            this.count = count;
            this.timestamp = timestamp;
            this.blacklisted = blacklisted;
        }
    }

    public static void init(File configDir) {
        storageFile = new File(configDir, "autotpa_data.txt");
        loadHistory();
    }

    public static void tick(MinecraftClient client) {
        if (!Autotpa.isMasterBotEnabled || client.player == null || client.world == null) return;

        // Если бот закупается или восстанавливается — НЕ отправляем TPA!
        if (AutoSellManager.isDeathRecoveryState()) return;

        boolean isConfirming = handleGuiConfirm(client);
        if (isConfirming) return;

        if (isWaitingItzNatsuu) {
            switch (natsuuState) {
                case WAITING_TP -> {
                    if (natsuuTimeoutTicks > 0) {
                        natsuuTimeoutTicks--;
                        if (client.player.squaredDistanceTo(lastTpaX, lastTpaY, lastTpaZ) > 25.0) {
                            Autotpa.sendFeedback("§a[AutoTPA] Телепорт успешен! Задержка перед /home 1...");
                            natsuuState = NatsuuState.DELAY_BEFORE_HOME;
                            natsuuDelayTicks = 40;
                        }
                    } else {
                        CombatManager.sendHome1WithVerification(client);
                        isWaitingItzNatsuu = false;
                        natsuuState = NatsuuState.IDLE;
                        noVictimTimerTicks = 1200;
                    }
                }
                case DELAY_BEFORE_HOME -> {
                    if (natsuuDelayTicks > 0) {
                        natsuuDelayTicks--;
                    } else {
                        CombatManager.sendHome1WithVerification(client);
                        natsuuState = NatsuuState.IDLE;
                        isWaitingItzNatsuu = false;
                        noVictimTimerTicks = 1200;
                    }
                }
            }
            return;
        }

        if (Autotpa.currentBotState != Autotpa.BotState.IDLE_TRAPPING) return;
        if (AutoSellManager.currentState != AutoSellManager.SellState.IDLE) return;
        if (CombatManager.isServerCombatActive()) return;
        if (CombatManager.isCombatActive(client) && CombatManager.isPlayerInsideTrap(CombatManager.findTarget(client))) return;
        if (CombatManager.isRepairingTrap || client.player.isTouchingWater()) return;
        if (CombatManager.isMendingToFull || CombatManager.isArmorNeedsMending(client)) return;
        if (CombatManager.isPostKillProcessing()) return;
        if (!CombatManager.isTrapReady(client)) return;

        // ВАЖНО: Проверка готовности инвентаря перед TPA! Если чего-то не хватает — СРАЗУ закупка!
        String missingReason = AutoSellManager.getRestockMissingReason(client);
        if (missingReason != null) {
            if (AutoSellManager.currentState == AutoSellManager.SellState.IDLE && AutoSellManager.globalCooldown <= 0) {
                AutoSellManager.startRestock(client, missingReason);
            }
            return;
        }

        if (CombatManager.getEmptySlotCount(client) <= 2 && (CombatManager.hasGroundLootInTrap(client) || AutoSellManager.hasSellableItems(client))) return;

        if (client.currentScreen != null) {
            if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen) {
                client.player.closeHandledScreen();
            } else if (!(client.currentScreen instanceof BotMenuScreen)) {
                client.setScreen(null);
            }
            return;
        }

        if (noVictimTimerTicks > 0) {
            noVictimTimerTicks--;
        } else {
            lastTpaX = client.player.getX();
            lastTpaY = client.player.getY();
            lastTpaZ = client.player.getZ();
            
            String master = AutoSellManager.getMasterName(client);

            Autotpa.sendFeedback("§e[AutoTPA] 1 минута без жертв. Отправляю /tpa " + master + "...");
            CommandQueue.send("tpa " + master);
            isWaitingItzNatsuu = true;
            natsuuState = NatsuuState.WAITING_TP;
            natsuuTimeoutTicks = 300;
            noVictimTimerTicks = 1200;
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
        } else if (!tpaTargetsQueue.isEmpty() && client.getNetworkHandler() != null) {
            String target = tpaTargetsQueue.pollLast();

            if (target != null && isEligible(target)) {
                CommandQueue.send("tpahere " + target);
                int count = recordSent(target);
                lastTargetPlayer = target;
                lastTargetTime = System.currentTimeMillis();
                Autotpa.sendFeedback("§e>>> Отправлен запрос §f[" + count + "/2]§e игроку: §b" + target);
                
                cooldownTicks = 14 + random.nextInt(3);
            }
        }
    }

    private static boolean handleGuiConfirm(MinecraftClient client) {
        if (client.currentScreen == null || client.player == null) {
            confirmDelayTicks = -1;
            return false;
        }

        Screen screen = client.currentScreen;
        String title = Autotpa.cleanText(screen.getTitle().getString()).toLowerCase();

        if (title.contains("информация") || title.contains("пользовательских")) {
            ButtonWidget back = findButton(screen, "назад", "back");
            if (back != null) back.onPress(null);
            return true;
        }

        if (screen instanceof GenericContainerScreen containerScreen) {
            int syncId = containerScreen.getScreenHandler().syncId;
            int totalSlots = containerScreen.getScreenHandler().slots.size();

            for (int i = 0; i < Math.min(totalSlots, 54); i++) {
                ItemStack stack = containerScreen.getScreenHandler().getSlot(i).getStack();
                if (stack.isEmpty()) continue;
                String name = Autotpa.cleanText(stack.getName().getString()).toLowerCase();

                if (isConfirmText(name)) {
                    if (confirmDelayTicks == -1) {
                        confirmDelayTicks = 2;
                    } else if (confirmDelayTicks > 0) {
                        confirmDelayTicks--;
                    } else if (confirmDelayTicks == 0) {
                        client.interactionManager.clickSlot(syncId, i, 0, SlotActionType.PICKUP, client.player);
                        confirmDelayTicks = -1;
                    }
                    return true;
                }
            }
        }

        ButtonWidget confirmBtn = findPositiveButton(screen);
        if (confirmBtn != null) {
            if (confirmDelayTicks == -1) {
                confirmDelayTicks = 2;
            } else if (confirmDelayTicks > 0) {
                confirmDelayTicks--;
            } else if (confirmDelayTicks == 0) {
                confirmBtn.onPress(null);
                confirmDelayTicks = -1;
            }
            return true;
        }

        return false;
    }

    private static boolean isConfirmText(String text) {
        if (text == null) return false;
        String clean = text.toLowerCase();
        return clean.contains("подтверд") || clean.contains("принять") || clean.contains("соглас")
                || clean.contains("confirm") || clean.contains("accept") || clean.contains("yes")
                || clean.equals("да");
    }

    private static ButtonWidget findPositiveButton(Screen screen) {
        for (ClickableWidget w : getAllWidgets(screen)) {
            String text = Autotpa.cleanText(w.getMessage().getString()).toLowerCase();
            if (isConfirmText(text)) {
                if (w instanceof ButtonWidget btn) return btn;
            }
        }
        return null;
    }

    private static ButtonWidget findButton(Screen screen, String... words) {
        for (ClickableWidget w : getAllWidgets(screen)) {
            String text = Autotpa.cleanText(w.getMessage().getString()).toLowerCase();
            for (String word : words) {
                if (text.contains(word) && w instanceof ButtonWidget btn) return btn;
            }
        }
        return null;
    }

    private static List<ClickableWidget> getAllWidgets(Screen screen) {
        List<ClickableWidget> result = new ArrayList<>();
        collectWidgets(screen, result, new HashSet<>());
        return result;
    }

    private static void collectWidgets(Element element, List<ClickableWidget> list, Set<Element> visited) {
        if (element == null || visited.contains(element)) return;
        visited.add(element);
        if (element instanceof ClickableWidget w) list.add(w);
        if (element instanceof ParentElement p) {
            for (Element child : p.children()) collectWidgets(child, list, visited);
        }
    }

    public static void tickGameMessage(String rawText, boolean overlay) {
        NatsuuManager.onGameMessage(rawText, overlay);
        if (!Autotpa.isMasterBotEnabled) return;
        
        String clean = Autotpa.cleanText(rawText);
        if (overlay) {
            Matcher m = COMBAT_PATTERN.matcher(clean.toLowerCase());
            if (m.find()) {
                try {
                    int seconds = Integer.parseInt(m.group(1));
                    CombatManager.setCombatExpiration(System.currentTimeMillis() + (seconds * 1000L));
                } catch (Exception ignored) {}
            }
        } else {
            checkIfPlayerDisabledTpa(clean);
            onChatMessage(rawText);
        }
    }

    private static void checkIfPlayerDisabledTpa(String rawText) {
        String text = rawText.toLowerCase();
        if (text.contains("отключил запросы") || text.contains("отключил tpa") || text.contains("отключил tpahere") || text.contains("disabled tpa")
                || text.contains("не в сети") || text.contains("не найден") || text.contains("not online") || text.contains("player not found")) {
            long now = System.currentTimeMillis();
            if (lastTargetPlayer != null && (now - lastTargetTime < 6000)) {
                blacklistPlayer(lastTargetPlayer);
                Autotpa.sendFeedback("§c[AutoTPA] Игрок §e" + lastTargetPlayer + " §cнедоступен/отключил TPA. Заблокирован на 6ч!");
                lastTargetPlayer = null;
            }
        }
    }

    private static void blacklistPlayer(String name) {
        String key = name.toLowerCase();
        playerHistory.put(key, new PlayerData(2, System.currentTimeMillis(), true));
        saveHistory();
    }

    public static void onChatMessage(String rawText) {
        if (!Autotpa.isMasterBotEnabled) return;

        MinecraftClient client = MinecraftClient.getInstance();
        String cleanText = Autotpa.cleanText(rawText);
        String targetName = extractUsername(cleanText);
        if (targetName == null) return;

        String withoutPluses = targetName.replaceAll("^\\++", "").trim();
        String sanitizedUsername = withoutPluses.replaceAll("[^a-zA-Z0-9_.]", "");
        if (sanitizedUsername.length() < 3 || sanitizedUsername.length() > 17) return;

        if (FriendManager.isFriend(sanitizedUsername)) return;
        if (client.player != null && sanitizedUsername.equalsIgnoreCase(client.player.getName().getString())) return;

        if (isEligible(sanitizedUsername)) {
            tpaTargetsQueue.remove(sanitizedUsername);
            if (tpaTargetsQueue.size() >= 10) {
                tpaTargetsQueue.pollFirst();
            }
            tpaTargetsQueue.addLast(sanitizedUsername);
        }
    }

    private static String extractUsername(String text) {
        int openBracket = text.indexOf('<');
        int closeBracket = text.indexOf('>', openBracket);
        if (openBracket != -1 && closeBracket != -1 && closeBracket > openBracket + 1) {
            return text.substring(openBracket + 1, closeBracket).trim();
        }

        String senderPart = text;
        if (text.contains(":")) {
            senderPart = text.split(":")[0];
        } else if (text.contains("»")) {
            senderPart = text.split("»")[0];
        } else {
            return null;
        }

        senderPart = senderPart.replaceAll("\\[.*?\\]", "").trim();
        String[] words = senderPart.split("\\s+");
        if (words.length > 0) {
            String possibleName = words[words.length - 1].replaceAll("[^a-zA-Z0-9_]", "");
            if (possibleName.length() >= 3 && possibleName.length() <= 16) {
                return possibleName;
            }
        }

        return null;
    }

    private static boolean isEligible(String name) {
        String key = name.toLowerCase();
        PlayerData data = playerHistory.get(key);
        if (data == null) return true;
        if (System.currentTimeMillis() - data.timestamp > SIX_HOURS_MS) {
            playerHistory.remove(key);
            saveHistory();
            return true;
        }
        return !data.blacklisted && data.count < 2;
    }

    private static int recordSent(String name) {
        String key = name.toLowerCase();
        PlayerData data = playerHistory.get(key);
        int count = (data != null && (System.currentTimeMillis() - data.timestamp <= SIX_HOURS_MS)) ? data.count + 1 : 1;
        playerHistory.put(key, new PlayerData(count, System.currentTimeMillis(), false));
        saveHistory();
        return count;
    }

    public static void resetSession() {
        tpaTargetsQueue.clear();
        playerHistory.clear();
        confirmDelayTicks = -1;
        isWaitingItzNatsuu = false;
        natsuuState = NatsuuState.IDLE;
        natsuuDelayTicks = 0;
        noVictimTimerTicks = 1200;
        lastTargetPlayer = null;
        lastTargetTime = 0;
    }

    private static void loadHistory() {
        if (!storageFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(storageFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(";");
                if (p.length >= 4) {
                    playerHistory.put(p[0].toLowerCase(), new PlayerData(Integer.parseInt(p[1]), Long.parseLong(p[2]), Boolean.parseBoolean(p[3])));
                }
            }
        } catch (Exception ignored) {}
    }

    private static void saveHistory() {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(storageFile), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, PlayerData> e : playerHistory.entrySet()) {
                w.write(e.getKey() + ";" + e.getValue().count + ";" + e.getValue().timestamp + ";" + e.getValue().blacklisted);
                w.newLine();
            }
        } catch (Exception ignored) {}
    }
}