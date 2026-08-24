package com.example.autotpa;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Autotpa implements ClientModInitializer {

	// Состояние мода (включен / выключен)
	private static boolean isEnabled = false;

	// Регулярное выражение для поиска ников вида <ник>
	private static final Pattern CHAT_PATTERN = Pattern.compile("^<([a-zA-Z0-9_.+]+)>");

	// Очередь ников
	private static final Queue<String> targetsQueue = new ArrayDeque<>();

	private static final Random random = new Random();

	// Задержка в тиках (20 тиков = 1 сек)
	private static int cooldownTicks = 0;

	// Кнопка переключения (по умолчанию буква K)
	private static KeyBinding toggleKeyBinding;

	@Override
	public void onInitializeClient() {
		// Регистрация клавиши K
		toggleKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.autotpa.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				"category.autotpa"
		));

		// Слушатель обычного чата
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			processIncomingMessage(message.getString());
		});

		// Слушатель игровых системных сообщений
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				processIncomingMessage(message.getString());
			}
		});

		// Тик клиента (20 раз в секунду)
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKeyBinding.wasPressed()) {
				isEnabled = !isEnabled;
				if (client.player != null) {
					client.player.sendMessage(
							Text.literal("§6[AutoTPA] §fСтатус: " + (isEnabled ? "§aВКЛЮЧЕН" : "§cВЫКЛЮЧЕН")),
							false
					);
				}
				if (!isEnabled) {
					targetsQueue.clear();
				}
			}

			if (!isEnabled) return;

			if (cooldownTicks > 0) {
				cooldownTicks--;
			} else if (!targetsQueue.isEmpty() && client.getNetworkHandler() != null) {
				String targetPlayer = targetsQueue.poll();

				// Отправка команды
				client.getNetworkHandler().sendCommand("tpahere " + targetPlayer);

				// Случайная задержка 1-2 секунды (20..40 тиков)
				cooldownTicks = 20 + random.nextInt(21);
			}
		});
	}

	private void processIncomingMessage(String rawText) {
		if (!isEnabled) return;

		String text = rawText.trim();
		Matcher matcher = CHAT_PATTERN.matcher(text);

		if (matcher.find()) {
			String rawUsername = matcher.group(1);

			// 1. Игнорируем ники, начинающиеся на точку (например, <.felergon>)
			if (rawUsername.startsWith(".")) {
				return;
			}

			// 2. Удаляем ведущие плюсы (<+Name>, <++Name> -> Name)
			String cleanUsername = rawUsername.replaceAll("^\\++", "");
			if (cleanUsername.isEmpty()) return;

			// 3. Случайный выбор каждого 1-3 игрока (шанс ~33%)
			if (random.nextInt(3) == 0) {
				if (!targetsQueue.contains(cleanUsername)) {
					if (targetsQueue.size() >= 5) {
						targetsQueue.poll();
					}
					targetsQueue.add(cleanUsername);
				}
			}
		}
	}
}