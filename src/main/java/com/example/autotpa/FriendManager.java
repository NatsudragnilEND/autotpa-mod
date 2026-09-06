package com.example.autotpa;

import net.minecraft.client.network.AbstractClientPlayerEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class FriendManager {

    private static final Set<String> friends = new HashSet<>();
    private static String primaryPartner = "itzNatsuu";
    private static File friendsFile;

    public static void init(File configDir) {
        friendsFile = new File(configDir, "friends.txt");
        loadFriends();
    }

    public static String getPrimaryPartner() {
        return primaryPartner;
    }

    public static void setPrimaryPartner(String name) {
        if (name != null && !name.trim().isEmpty()) {
            primaryPartner = name.trim();
            addFriend(primaryPartner);
            saveFriends();
        }
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        // Strip color codes, brackets, clan tags, and non-alphanumeric chars
        return Autotpa.cleanText(input)
                .replaceAll("(?i)[§&][0-9a-z]", "")
                .replaceAll("[^a-zA-Z0-9_.]", "")
                .toLowerCase()
                .trim();
    }

    public static boolean isFriend(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return false;
        String cleanTarget = sanitize(rawName);

        if (cleanTarget.isEmpty()) return false;

        for (String f : friends) {
            String cleanFriend = sanitize(f);
            if (cleanFriend.isEmpty()) continue;

            // Direct match, or target contains friend name (e.g. [VIP] Vuk1235)
            if (cleanTarget.equals(cleanFriend) || cleanTarget.contains(cleanFriend)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPlayerFriend(AbstractClientPlayerEntity player) {
        if (player == null) return false;

        // 1. Check GameProfile name
        try {
            if (player.getGameProfile() != null && isFriend(player.getGameProfile().name())) {
                return true;
            }
        } catch (Throwable ignored) {}

        // 2. Check Display Name
        if (isFriend(player.getName().getString())) {
            return true;
        }

        // 3. Check Custom Name
        if (player.getCustomName() != null && isFriend(player.getCustomName().getString())) {
            return true;
        }

        return false;
    }

    public static void addFriend(String username) {
        if (username == null || username.trim().isEmpty()) return;
        friends.add(username.trim().toLowerCase());
        saveFriends();
    }

    public static void removeFriend(String username) {
        if (username == null) return;
        friends.remove(username.trim().toLowerCase());
        saveFriends();
    }

    public static Set<String> getFriends() {
        return friends;
    }

    public static void loadFriends() {
        friends.clear();
        friends.add("itznatsuu");

        if (!friendsFile.exists()) {
            saveFriends();
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(friendsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.toLowerCase().startsWith("partner:")) {
                    primaryPartner = line.substring(8).trim();
                    friends.add(primaryPartner.toLowerCase());
                } else {
                    friends.add(line.toLowerCase());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        System.out.println("[AutoTPA] Loaded friends: " + friends);
    }

    public static void saveFriends() {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(friendsFile), StandardCharsets.UTF_8))) {
            writer.write("partner:" + primaryPartner);
            writer.newLine();
            for (String friend : friends) {
                if (!friend.equalsIgnoreCase(primaryPartner)) {
                    writer.write(friend);
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}