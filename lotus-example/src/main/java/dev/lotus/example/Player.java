package dev.lotus.example;

/** Stand-in for a game API type you don't own (e.g. org.bukkit.entity.Player). */
public class Player {
    private final String name;
    private double health = 20.0;

    public Player(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    public void sendMessage(String message) {
        System.out.println("[to " + name + "] " + message);
    }
}
