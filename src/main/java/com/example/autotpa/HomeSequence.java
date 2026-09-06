package com.example.autotpa;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeSequence {
    private enum State {
        IDLE,
        OPEN_MAIN_MENU,
        CLICK_DOM_1,
        CLICK_DELETE_BUTTON,
        CLICK_CONFIRM_DELETE,
        REOPEN_FOR_NEW_HOME,
        CLICK_NEW_HOME_BUTTON,
        FINISH
    }

    private static State currentState = State.IDLE;
    private static int ticks = 0;

    public static void startSequence(MinecraftClient client) {
        CommandQueue.send("home");
        currentState = State.CLICK_DOM_1;
        ticks = 20; // 1 сек на открытие /home
        Autotpa.sendFeedback("§e[HomeGUI] Открываю меню /home для сброса Дома 1...");
    }

    public static boolean isIdle() {
        return currentState == State.IDLE;
    }

    public static void tick(MinecraftClient client) {
        if (currentState == State.IDLE || client.player == null) return;

        if (ticks > 0) {
            ticks--;
            return;
        }

        Screen screen = client.currentScreen;

        switch (currentState) {
            // ШАГ 1: В главном меню /home нажимаем "Дом 1" (Скриншот 3)
            // (Либо если Дома 1 не было, сразу жмем "Новый дом")
            case CLICK_DOM_1 -> {
                if (screen != null) {
                    if (clickButton(screen, "Новый дом", "новый дом", "new home")) {
                        // Дома 1 не было, сразу создался новый!
                        currentState = State.FINISH;
                        ticks = 15;
                    } else if (clickButton(screen, "Дом 1", "дом 1", "home 1")) {
                        currentState = State.CLICK_DELETE_BUTTON;
                        ticks = 15;
                    }
                } else {
                    CommandQueue.send("home");
                    ticks = 20;
                }
            }

            // ШАГ 2: В меню Дома 1 нажимаем красную кнопку "Удалить" (Скриншот 1)
            case CLICK_DELETE_BUTTON -> {
                if (screen != null) {
                    if (clickButton(screen, "Удалить", "удалить", "delete")) {
                        currentState = State.CLICK_CONFIRM_DELETE;
                        ticks = 15;
                    }
                }
            }

            // ШАГ 3: В окне подтверждения "Удалить Дом 1?" нажимаем "Удалить" (Скриншот 2)
            case CLICK_CONFIRM_DELETE -> {
                if (screen != null) {
                    if (clickButton(screen, "Удалить", "удалить", "delete")) {
                        currentState = State.REOPEN_FOR_NEW_HOME;
                        ticks = 20;
                    }
                }
            }

            // ШАГ 4: Снова открываем /home, где теперь появилась кнопка "Новый дом"
            case REOPEN_FOR_NEW_HOME -> {
                CommandQueue.send("home");
                currentState = State.CLICK_NEW_HOME_BUTTON;
                ticks = 20;
            }

            // ШАГ 5: Нажимаем кнопку "Новый дом" (Скриншот 4)
            case CLICK_NEW_HOME_BUTTON -> {
                if (screen != null) {
                    if (clickButton(screen, "Новый дом", "новый дом", "new home", "установить")) {
                        currentState = State.FINISH;
                        ticks = 15;
                    }
                } else {
                    CommandQueue.send("home");
                    ticks = 20;
                }
            }

            // ШАГ 6: Закрываем меню и завершаем
            case FINISH -> {
                if (client.currentScreen != null) {
                    client.setScreen(null);
                }
                currentState = State.IDLE;
                Autotpa.sendFeedback("§a[HomeGUI] Новый Дом 1 успешно сохранен через меню!");
            }
        }
    }

    // Поиск и нажатие нужной кнопки на экране
    private static boolean clickButton(Screen screen, String... matchTexts) {
        List<ClickableWidget> widgets = new ArrayList<>();
        collectWidgets(screen, widgets, new HashSet<>());

        for (ClickableWidget w : widgets) {
            if (w instanceof ButtonWidget btn) {
                String cleanBtnText = Autotpa.cleanText(btn.getMessage().getString()).toLowerCase();
                for (String match : matchTexts) {
                    String cleanMatch = match.toLowerCase();
                    if (cleanBtnText.equals(cleanMatch) || cleanBtnText.contains(cleanMatch)) {
                        btn.onPress(null);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void collectWidgets(Element element, List<ClickableWidget> list, Set<Element> visited) {
        if (element == null || visited.contains(element)) return;
        visited.add(element);

        if (element instanceof ClickableWidget widget) {
            list.add(widget);
        }
        if (element instanceof ParentElement parent) {
            for (Element child : parent.children()) {
                collectWidgets(child, list, visited);
            }
        }
    }

    public static void reset() {
        currentState = State.IDLE;
        ticks = 0;
    }
}