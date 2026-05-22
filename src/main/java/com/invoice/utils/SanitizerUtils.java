package com.invoice.utils;

public class SanitizerUtils {

    public static String sanitize(String input) {
        if (input == null) return null;
        return input.trim().replaceAll("\\s{2,}", " ");
    }
}
