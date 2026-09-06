package com.example.autotpa;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import java.io.File;
import java.util.regex.Pattern;

public class Autotpa implements ClientModInitializer {

    public enum BotState {
        IDLE_TRAPPING,
        DISCONNECTED_WAITING,
        RECONNECTED_SETUP,
        ESCAPE_EATING_CHORUS,
        ESCAPE_RUNNING,
        STARTING_REGEAR, 
        ESCAPE_HOME3_WAIT,
        TRAP_BUILDING,
        STARTING_SETUP_HOME1 // Безопасный запуск: сначала /home 1, затем ловля
    }

    public static BotState currentBotState = BotState.IDLE_TRAPPING;
    public static boolean isMasterBotEnabled = false;

    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)[§&][0-9A-FK-OR]");
    public static KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        File configDir = new File(client.runDirectory, "config");
        if (!configDir.exists()) configDir.mkdirs();
        FriendManager.init(configDir);
        TpaManager.init(configDir);
        CombatManager.init(configDir);
        TrapBuilder.init(configDir);

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autotpa.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyBinding.Category.MISC));

        // 1. Перехват сообщений обычного чата игроков
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, time) -> {
            String text = msg.getString();
            TpaManager.onChatMessage(text);
            CombatManager.onGameMessage(text);
        });

        // 2. Перехват системных сообщений сервера (ошибки /home 1, обслуживание, очереди)
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            String text = msg.getString();
            TpaManager.tickGameMessage(text, overlay);
            CombatManager.onGameMessage(text);
        });

        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            while (menuKey.wasPressed()) {
                openMenu(c);
            }

            // 1. ВЫЗЫВАЕМ NatsuuManager ПЕРВЫМ (чтобы он работал во время экрана смерти и вылета!)
            NatsuuManager.tick(c);
             
            // 2. Авто-респавн при смерти (для основного бота)
            if (c.currentScreen instanceof DeathScreen) {
                if (isMasterBotEnabled && !AutoSellManager.isDeathRecoveryState()) {
                    c.player.requestRespawn();
                    AutoSellManager.startDeathRecovery(c);
                    CombatManager.resetSession(c);
                    TpaManager.resetSession();
                    TrapBuilder.reset();
                    CommandQueue.clear();
                }
                return;
            }

            // 3. АВТО-ПЕРЕХВАТ ЛЮБОГО КИКА С СЕРВЕРА (для основного бота)
            if (isMasterBotEnabled && (c.currentScreen instanceof DisconnectedScreen || (c.player == null && c.world == null))) {
                if (currentBotState != BotState.DISCONNECTED_WAITING && currentBotState != BotState.RECONNECTED_SETUP) {
                    currentBotState = BotState.DISCONNECTED_WAITING;
                }
                CombatManager.handleAutoReconnect(c);
                return;
            }

            if (c.player == null || c.world == null) return;
            
            CommandQueue.tick(c);
            CombatManager.tick(c);
            AutoSellManager.tick(c);
             AhSniperManager.tick(c);
            TrapBuilder.tick(c);
            HomeSequence.tick(c);
            TpaManager.tick(c);
        });
    }

    public static void openMenu(MinecraftClient client) {
        isMasterBotEnabled = false;
        currentBotState = BotState.IDLE_TRAPPING;
        AutoSellManager.currentState = AutoSellManager.SellState.IDLE;
        CombatManager.resetSession(client);
        TpaManager.resetSession();
        TrapBuilder.reset();
        HomeSequence.reset();
        CommandQueue.clear();
        
        client.setScreen(new BotMenuScreen(Text.literal("AutoTPA Control Panel")));
    }

    public static String cleanText(String text) {
        if (text == null) return "";
        String stripped = net.minecraft.util.Formatting.strip(text);
        if (stripped == null) stripped = text;
        return stripped.replaceAll("(?i)[§&][a-z0-9]", "").replaceAll("[\\u00A0\\s]+", " ").trim();
    }

    public static void sendFeedback(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null && client.inGameHud.getChatHud() != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal("§6[AutoTPA] §f" + message));
        }
    }
}