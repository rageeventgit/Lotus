package dev.lotus.example;

import dev.lotus.Extension;
import dev.lotus.This;

@Extension(Player.class)
public final class PlayerExtension {
    private PlayerExtension() {
    }

    /** Instance extension: {@code player.heal(5)}. */
    public static void heal(@This Player player, double amount) {
        player.setHealth(Math.min(20.0, player.getHealth() + amount));
    }

    /** Instance extension: {@code player.isAlive()}. */
    public static boolean isAlive(@This Player player) {
        return player.getHealth() > 0;
    }

    /** Varargs instance extension: {@code player.sendMessages("a", "b")}. */
    public static void sendMessages(@This Player player, String... messages) {
        for (String message : messages) {
            player.sendMessage(message);
        }
    }

    /** Static extension (no {@code @This}): {@code Player.create("Steve")}. */
    public static Player create(String name) {
        return new Player(name);
    }
}
