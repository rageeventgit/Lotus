package dev.lotus.example;

import dev.lotus.DisableTemplates;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) {
        Player player = Player.create("Steve");          // static extension
        player.setHealth(10);
        player.heal(5);                                  // instance extension

        String name = player.getName();
        double health = player.getHealth();
        System.out.println("$name has $health health");             // simple templates
        System.out.println("Alive: ${player.isAlive()}");           // expression template
        System.out.println("Shout: ${name.shout()}");               // extension inside a template
        String word = "racecar";
        System.out.println("$word ${word.isPalindrome() ? \"is\" : \"is not\"} a palindrome");
        System.out.println("hi".repeatWith(3, "-"));

        List<String> names = Arrays.asList("Alex", "Steve", "Notch");
        String first = names.firstOrNull();               // generic extension, inferred as String
        List<String> shortNames = names.filtered(n -> n.length() <= 4);
        System.out.println("first=$first short=$shortNames");
        System.out.println(names.stream().map(String::shout).collect(Collectors.toList()));   // method reference to an extension

        player.sendMessages("Welcome, $name!", "Costs $5, not ${5 * 2}");
        new Main().greet(player);
        System.out.println(literal());
    }

    void greet(Player player) {
        player.sendMessage("Hello ${player.getName().shout()}");
    }

    @DisableTemplates
    static String literal() {
        return "Templates off here: ${not.evaluated}";
    }
}
