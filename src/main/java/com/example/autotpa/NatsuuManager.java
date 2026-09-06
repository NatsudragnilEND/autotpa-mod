package com.example.autotpa;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;

public class NatsuuManager {

    public static boolean isNatsuuModeEnabled = false;
    private static boolean autoDetected = false;

    private static int respawnCooldownTicks = 0;
    private static boolean pendingTpAuto = false;
    private static int tpAutoDelayTicks = 0;
    
    // Таймеры авто-реконнекта
    private static int reconnectTimerSeconds = 60;
    private static int reconnectTickCounter = 20;

    // --- ПЕРЕМЕННЫЕ ПРОВЕРКИ /TPAUTO РАЗ В 10 МИНУТ ---
    private static int tpAutoCheckIntervalTicks = 12000; // 10 минут (10 * 60 * 20 тиков)
    private static boolean isCheckingTpAuto = false;
    private static int checkTimeoutTicks = 0;
    private static int reEnableDelayTicks = 0;

    public static void tick(MinecraftClient client) {
        if (client == null) return;

        // 1. АВТО-ОПРЕДЕЛЕНИЕ ПО НИКУ
        if (!autoDetected && client.player != null) {
            String nick = client.player.getName().getString();
            if (nick.equalsIgnoreCase("itzNatsuu")) {
                isNatsuuModeEnabled = true;
                autoDetected = true;
                Autotpa.sendFeedback("§a[NatsuuManager] Аккаунт itzNatsuu распознан! Режим включен.");
            }
        }

        if (!isNatsuuModeEnabled) return;

        // 2. АВТО-РЕКОННЕКТ ПРИ ВЫЛЕТЕ (РАЗ В 60 СЕКУНД)
        if (client.world == null || client.player == null || client.currentScreen instanceof DisconnectedScreen) {
            handleReconnect(client);
            return;
        }

        // 3. АВТО-ВОЗРОЖДЕНИЕ ПРИ СМЕРТИ
        boolean isDead = client.player.isDead() 
                || client.player.getHealth() <= 0.0f 
                || client.currentScreen instanceof DeathScreen;

        if (isDead) {
            if (respawnCooldownTicks > 0) {
                respawnCooldownTicks--;
            } else {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
                }
                if (client.player != null) {
                    client.player.requestRespawn();
                }
                if (client.currentScreen instanceof DeathScreen) {
                    client.setScreen(null);
                }

                pendingTpAuto = true;
                tpAutoDelayTicks = 25; // 1.25 сек паузы после респавна
                respawnCooldownTicks = 30;
                Autotpa.sendFeedback("§c[NatsuuManager] Смерть зафиксирована! Мгновенное возрождение...");
            }
            return;
        } else {
            respawnCooldownTicks = 0;
        }

        // 4. ОТПРАВКА /tpauto ПОСЛЕ СМЕРТИ
        if (pendingTpAuto) {
            if (tpAutoDelayTicks > 0) {
                tpAutoDelayTicks--;
            } else {
                sendDirectCommand(client, "tpauto");
                Autotpa.sendFeedback("§a[NatsuuManager] Команда /tpauto отправлена после респавна!");
                pendingTpAuto = false;
                tpAutoCheckIntervalTicks = 12000; // Сбрасываем 10-минутный таймер
            }
        }

        // 5. ПРОВЕРКА /tpauto КАЖДЫЕ 10 МИНУТ
        if (tpAutoCheckIntervalTicks > 0) {
            tpAutoCheckIntervalTicks--;
        } else {
            // 10 минут прошло -> пишем /tpauto для проверки
            isCheckingTpAuto = true;
            checkTimeoutTicks = 60; // Ждем ответ сервера 3 секунды
            tpAutoCheckIntervalTicks = 12000; // Снова ставим 10 минут
            sendDirectCommand(client, "tpauto");
            Autotpa.sendFeedback("§e[NatsuuManager] 10 минут прошло. Проверяю статус /tpauto...");
        }

        // Таймаут ожидания ответа
        if (isCheckingTpAuto) {
            if (checkTimeoutTicks > 0) {
                checkTimeoutTicks--;
            } else {
                isCheckingTpAuto = false;
            }
        }

        // Включение обратно, если мы случайно выключили активный tpauto
        if (reEnableDelayTicks > 0) {
            reEnableDelayTicks--;
            if (reEnableDelayTicks == 0) {
                sendDirectCommand(client, "tpauto");
                Autotpa.sendFeedback("§a[NatsuuManager] tpauto успешно включён обратно!");
            }
        }
    }

    // --- ПАРСИНГ ОТВЕТА СЕРВЕРА НА /tpauto ---
    public static void onGameMessage(String rawText, boolean overlay) {
        if (!isNatsuuModeEnabled || !isCheckingTpAuto) return;

        String clean = Autotpa.cleanText(rawText).toLowerCase();

        // 1. ЕСЛИ СЕРВЕР ОТВЕТИЛ "ВЫКЛЮЧИЛИ" -> ЗНАЧИТ ОН БЫЛ ВКЛЮЧЕН, ВКЛЮЧАЕМ ОБРАТНО!
        if (clean.contains("вы выключили tpauto") || clean.contains("выключили tpauto") || clean.contains("disabled tpauto")) {
            isCheckingTpAuto = false;
            reEnableDelayTicks = 10; // Через 0.5 сек шлем /tpauto второй раз для включения
            Autotpa.sendFeedback("§e[NatsuuManager] tpauto был активен (выключился) -> включаю обратно...");
        }
        // 2. ЕСЛИ СЕРВЕР ОТВЕТИЛ "ВКЛЮЧИЛИ" -> ЗНАЧИТ ОН БЫЛ ВЫКЛЮЧЕН, ТЕПЕРЬ ОН ВКЛЮЧЕН!
        else if (clean.contains("вы включили tpauto") || clean.contains("у вас включён tpauto") || clean.contains("включили tpauto") || clean.contains("enabled tpauto")) {
            isCheckingTpAuto = false;
            Autotpa.sendFeedback("§a[NatsuuManager] tpauto был выключен -> теперь успешно ВКЛЮЧЁН!");
        }
    }

    public static void toggleNatsuuMode() {
        isNatsuuModeEnabled = !isNatsuuModeEnabled;
        if (isNatsuuModeEnabled) {
            Autotpa.sendFeedback("§a[NatsuuManager] Режим itzNatsuu: §2ВКЛЮЧЕН §a(Авто-респавн + проверка /tpauto раз в 10м)");
        } else {
            Autotpa.sendFeedback("§c[NatsuuManager] Режим itzNatsuu: §4ВЫКЛЮЧЕН");
        }
    }

    private static void sendDirectCommand(MinecraftClient client, String command) {
        String cleanCmd = command.startsWith("/") ? command.substring(1) : command;
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(cleanCmd);
        } else {
            CommandQueue.send(cleanCmd);
        }
    }

    private static void handleReconnect(MinecraftClient client) {
        if (reconnectTickCounter > 0) {
            reconnectTickCounter--;
        } else {
            reconnectTickCounter = 20;
            reconnectTimerSeconds--;

            if (reconnectTimerSeconds % 15 == 0 || reconnectTimerSeconds <= 5) {
                Autotpa.sendFeedback("§e[Natsuu Reconnect] Переподключение через " + reconnectTimerSeconds + "с...");
            }

            if (reconnectTimerSeconds <= 0) {
                reconnectTimerSeconds = 60;
                Autotpa.sendFeedback("§a[Natsuu Reconnect] 1 минута прошла! Подключаюсь к donutsmp.net...");
                ServerInfo info = new ServerInfo("DonutSMP", "donutsmp.net", ServerInfo.ServerType.OTHER);
                ConnectScreen.connect(new TitleScreen(), client, ServerAddress.parse("donutsmp.net"), info, false, null);
            }
        }
    }
}