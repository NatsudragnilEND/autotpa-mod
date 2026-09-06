package com.example.autotpa;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class BotMenuScreen extends Screen {

    public BotMenuScreen(Text title) {
        super(title);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 4 - 25; // Слегка приподняли, чтобы все кнопки поместились
        int btnWidth = 220;
        int btnHeight = 20;
        int gap = 24;
        // 1. ВКЛЮЧЕНИЕ / ВЫКЛЮЧЕНИЕ ОСНОВНОГО БОТА
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(Autotpa.isMasterBotEnabled ? "§cTurn OFF Master Bot" : "§aTurn ON Master Bot"),
                btn -> {
                    Autotpa.isMasterBotEnabled = !Autotpa.isMasterBotEnabled;
                    if (Autotpa.isMasterBotEnabled && this.client != null) {
                        CombatManager.startBotSession(this.client);
                    }
                    btn.setMessage(Text.literal(Autotpa.isMasterBotEnabled ? "§cTurn OFF Master Bot" : "§aTurn ON Master Bot"));
                }).dimensions(centerX - btnWidth / 2, startY, btnWidth, btnHeight).build());

        // 2. РУЧНОЙ ЗАПУСК ТОРГОВЛИ И ЗАКУПКИ
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Start AutoSell / Buy"),
                btn -> {
                    if (this.client != null) {
                        this.client.setScreen(null);
                        Autotpa.isMasterBotEnabled = true;
                        AutoSellManager.startAutoSell(this.client);
                    }
                }).dimensions(centerX - btnWidth / 2, startY + gap, btnWidth, btnHeight).build());

        // 3. СОХРАНЕНИЕ ТЕКУЩЕЙ ЛОВУШКИ
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Save Current Trap Area"),
                btn -> {
                    if (this.client != null) {
                        CombatManager.saveTrap(this.client);
                        this.client.setScreen(null);
                    }
                }).dimensions(centerX - btnWidth / 2, startY + gap * 2, btnWidth, btnHeight).build());
                
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(AhSniperManager.isSniperEnabled ? "§cStop AH Sniper" : "§aStart AH Sniper"),
                btn -> {
                    if (AhSniperManager.isSniperEnabled) {
                        AhSniperManager.stopSniper();
                    } else {
                        if (this.client != null) this.client.setScreen(null);
                        AhSniperManager.startSniper();
                    }
                    btn.setMessage(Text.literal(AhSniperManager.isSniperEnabled ? "§cStop AH Sniper" : "§aStart AH Sniper"));
                }).dimensions(centerX - btnWidth / 2, startY + gap * 7, btnWidth, btnHeight).build());
        // 4. ТЕСТ ПОБЕГА (ХОРУС + ПЕРЛЫ + ТПА)
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Test Escape (Run & Pearl)"),
                btn -> {
                    if (this.client != null) {
                        this.client.setScreen(null);
                        Autotpa.isMasterBotEnabled = true;
                        CombatManager.setCombatExpiration(System.currentTimeMillis() + 10000L);
                        CombatManager.startEscape(this.client);
                    }
                }).dimensions(centerX - btnWidth / 2, startY + gap * 3, btnWidth, btnHeight).build());

        // 5. ТЕСТ ПОСТРОЙКИ ЛОВУШКИ ЧЕРЕЗ RTP
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§d[TEST] Auto Trap Builder (/rtp)"),
                btn -> {
                    if (this.client != null) {
                        this.client.setScreen(null);
                        Autotpa.isMasterBotEnabled = true;
                        TrapBuilder.start(this.client);
                    }
                }).dimensions(centerX - btnWidth / 2, startY + gap * 4, btnWidth, btnHeight).build());

        // 6. ПЕРЕКЛЮЧАТЕЛЬ РЕЖИМА ДЛЯ АККАУНТА itzNatsuu
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(NatsuuManager.isNatsuuModeEnabled ? "§cTurn OFF itzNatsuu Mode" : "§aTurn ON itzNatsuu Mode"),
                btn -> {
                    NatsuuManager.toggleNatsuuMode();
                    btn.setMessage(Text.literal(NatsuuManager.isNatsuuModeEnabled ? "§cTurn OFF itzNatsuu Mode" : "§aTurn ON itzNatsuu Mode"));
                }).dimensions(centerX - btnWidth / 2, startY + gap * 5, btnWidth, btnHeight).build());

        // 7. ДЕБАГ: СТАТУС БОЯ (PVP)
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("§6[DEBUG] Проверить статус боя (PVP)"),
                btn -> {
                    if (this.client != null) {
                        Autotpa.sendFeedback(CombatManager.getDebugCombatStatus(this.client));
                    }
                }).dimensions(centerX - btnWidth / 2, startY + gap * 6, btnWidth, btnHeight).build());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}