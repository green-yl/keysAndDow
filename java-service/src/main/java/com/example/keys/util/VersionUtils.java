package com.example.keys.util;

public final class VersionUtils {
    private VersionUtils() {}

    public static int compare(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;

        v1 = v1.toLowerCase().replace("v", "").trim();
        v2 = v2.toLowerCase().replace("v", "").trim();

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parsePart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parsePart(parts2[i]) : 0;
            if (num1 != num2) return num1 - num2;
        }
        return 0;
    }

    public static String increment(String currentVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) {
            return "1.0.1";
        }
        try {
            String[] parts = currentVersion.split("\\.");
            if (parts.length >= 3) {
                int patch = Integer.parseInt(parts[parts.length - 1]) + 1;
                parts[parts.length - 1] = String.valueOf(patch);
                return String.join(".", parts);
            } else if (parts.length == 2) {
                int minor = Integer.parseInt(parts[1]) + 1;
                return parts[0] + "." + minor + ".0";
            } else {
                return currentVersion + ".1";
            }
        } catch (NumberFormatException e) {
            return currentVersion + ".1";
        }
    }

    private static int parsePart(String part) {
        try {
            String numPart = part.replaceAll("[^0-9].*", "");
            return numPart.isEmpty() ? 0 : Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
