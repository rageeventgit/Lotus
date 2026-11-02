package dev.lotus.example;

import dev.lotus.Extension;
import dev.lotus.This;

/** Extensions work on JDK types too. */
@Extension(String.class)
public final class StringExtensions {
    private StringExtensions() {
    }

    public static String shout(@This String text) {
        return text.toUpperCase() + "!";
    }

    public static boolean isPalindrome(@This String text) {
        return new StringBuilder(text).reverse().toString().equalsIgnoreCase(text);
    }

    public static String repeatWith(@This String text, int times, String separator) {
        return String.join(separator, java.util.Collections.nCopies(times, text));
    }
}
