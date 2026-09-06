package com.example.autotpa;

import net.minecraft.client.MinecraftClient;
import java.util.ArrayDeque;
import java.util.Queue;

public class CommandQueue {
    private static final Queue<String> queue = new ArrayDeque<>();
    private static int delayTicks = 0;
    private static final int MIN_COMMAND_DELAY = 6; // 0.3 сек задержка

    public static void send(String command) {
        if (command == null || command.trim().isEmpty()) return;
        String cleanCmd = command.startsWith("/") ? command.substring(1).trim() : command.trim();
        
        // ЗАЩИТА: не добавляем команду, если она уже ожидает в очереди (устраняет спам /shop)
        if (!queue.contains(cleanCmd)) {
            queue.add(cleanCmd);
        }
    }
    public static void tick(MinecraftClient client) {
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (!queue.isEmpty() && client.getNetworkHandler() != null) {
            String nextCmd = queue.poll();
            client.getNetworkHandler().sendChatCommand(nextCmd);
            delayTicks = MIN_COMMAND_DELAY;
        }
    }

    public static void clear() {
        queue.clear();
        delayTicks = 0;
    }

    public static boolean isEmpty() {
        return queue.isEmpty();
    }
}