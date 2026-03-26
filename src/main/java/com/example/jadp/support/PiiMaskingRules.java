package com.example.jadp.support;

import com.example.jadp.model.PiiType;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class PiiMaskingRules {

    private PiiMaskingRules() {
    }

    public static String mask(PiiType type, String value) {
        return switch (type) {
            case RESIDENT_REGISTRATION_NUMBER, FOREIGNER_REGISTRATION_NUMBER -> maskFront6Back7(value);
            case DRIVER_LICENSE_NUMBER -> maskDriverLicense(value);
            case PASSPORT_NUMBER -> maskPassport(value);
            case MOBILE_PHONE_NUMBER, LANDLINE_PHONE_NUMBER -> maskPhone(value);
            case CREDIT_CARD_NUMBER -> maskCard(value);
            case BANK_ACCOUNT_NUMBER -> maskAccount(value);
            case PERSON_NAME -> maskKoreanName(value);
            case EMAIL_ADDRESS -> maskEmail(value);
            case IP_ADDRESS -> maskIp(value);
            case STREET_ADDRESS -> maskAddress(value);
        };
    }

    public static String maskFront6Back7(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 13) {
            return value;
        }
        return digits.substring(0, 6) + "-*******";
    }

    public static String maskDriverLicense(String value) {
        String[] groups = value.split("-");
        if (groups.length == 4 && groups[2].length() == 6) {
            groups[2] = "******";
            return String.join("-", groups);
        }
        return value;
    }

    public static String maskPassport(String value) {
        if (!StringUtils.hasText(value) || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, value.length() - 4) + "****";
    }

    public static String maskPhone(String value) {
        String[] groups = value.split("[- ]+");
        if (groups.length >= 2) {
            groups[groups.length - 1] = "****";
            return String.join("-", groups);
        }
        return value;
    }

    public static String maskCard(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 13) {
            return value;
        }
        return "****-****-****-" + digits.substring(digits.length() - 4);
    }

    public static String maskAccount(String value) {
        String[] groups = value.split("[- ]+");
        if (groups.length > 1) {
            for (int i = 0; i < groups.length - 1; i++) {
                groups[i] = groups[i].replaceAll("\\d", "*");
            }
            return String.join("-", groups);
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() <= 5) {
            return "*".repeat(digits.length());
        }
        return digits.substring(0, Math.max(0, digits.length() - 5)).replaceAll("\\d", "*")
                + digits.substring(digits.length() - 5);
    }

    public static String maskKoreanName(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        int visibleIndex = 0;
        for (char ch : value.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                builder.append(ch);
                continue;
            }
            visibleIndex++;
            builder.append(visibleIndex % 2 == 0 ? '*' : ch);
        }
        return builder.toString();
    }

    public static String maskEmail(String value) {
        String[] tokens = value.split("@", 2);
        if (tokens.length != 2) {
            return value;
        }
        String local = tokens[0];
        String domain = tokens[1];
        if (local.length() <= 4) {
            return "****@" + domain;
        }
        return local.substring(0, 3) + "*".repeat(local.length() - 3) + "@" + domain;
    }

    public static String maskIp(String value) {
        String[] groups = value.split("\\.");
        if (groups.length != 4) {
            return value;
        }
        groups[2] = "***";
        return String.join(".", groups);
    }

    public static String maskAddress(String value) {
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length <= 3) {
            return value;
        }
        int keepCount = 3;
        if (tokens.length > 3 && tokens[2].matches(".*(로|길|대로)$")) {
            keepCount = 3;
        } else if (tokens.length > 3 && tokens[3].matches(".*(로|길|대로)$")) {
            keepCount = 4;
        }
        String kept = Arrays.stream(tokens, 0, Math.min(keepCount, tokens.length))
                .collect(Collectors.joining(" "));
        return kept + " *****";
    }
}
