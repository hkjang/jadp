package com.example.jadp.support;

import com.example.jadp.model.PiiType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PiiPatternMatcher {

    private static final String LABEL_PREFIX = "(?<![가-힣A-Za-z0-9])";
    private static final Pattern RRN_PATTERN = Pattern.compile("\\b\\d{6}\\s*-\\s*\\d{7}\\b");
    private static final Pattern DRIVER_PATTERN = Pattern.compile("\\b\\d{2}-\\d{2}-\\d{6}-\\d{2}\\b");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("\\b01[016789]\\s*-?\\s*\\d{3,4}\\s*-?\\s*\\d{4}\\b");
    private static final Pattern LANDLINE_PATTERN = Pattern.compile("\\b0\\d{1,2}\\s*-?\\s*\\d{3,4}\\s*-?\\s*\\d{4}\\b");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(?:\\d{4}[- ]?){3}\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern PASSPORT_LABEL_PATTERN = Pattern.compile(LABEL_PREFIX + "(여권번호\\s*[:：]\\s*)([A-Z0-9]{8,9})(?=$|\\s|[,.)])");
    private static final Pattern ACCOUNT_LABEL_PATTERN = Pattern.compile(LABEL_PREFIX + "(계좌번호\\s*[:：]\\s*)([0-9][0-9\\- ]{6,}[0-9])(?=$|\\s|[,.)])");
    private static final Pattern NAME_LABEL_PATTERN = Pattern.compile(LABEL_PREFIX + "((?:성명|이름|대표자)\\s*[:：]\\s*)([가-힣]{2,4})(?=$|\\s|[,.)])");
    private static final Pattern ADDRESS_LABEL_PATTERN = Pattern.compile(LABEL_PREFIX + "((?:주소|도로명주소)\\s*[:：]\\s*)(.+)$");

    private PiiPatternMatcher() {
    }

    public static List<PiiTextMatch> findMatches(String text) {
        List<PiiTextMatch> matches = new ArrayList<>();
        addResidentLike(matches, text);
        addRegex(matches, text, DRIVER_PATTERN, PiiType.DRIVER_LICENSE_NUMBER, "운전면허번호");
        addPassport(matches, text);
        addRegex(matches, text, MOBILE_PATTERN, PiiType.MOBILE_PHONE_NUMBER, "휴대폰번호");
        addLandline(matches, text);
        addCards(matches, text);
        addAccount(matches, text);
        addName(matches, text);
        addRegex(matches, text, EMAIL_PATTERN, PiiType.EMAIL_ADDRESS, "이메일");
        addIp(matches, text);
        addAddress(matches, text);

        return matches.stream()
                .sorted(Comparator.comparingInt(PiiTextMatch::start).thenComparingInt(PiiTextMatch::end))
                .toList();
    }

    private static void addResidentLike(List<PiiTextMatch> matches, String text) {
        Matcher matcher = RRN_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String digits = raw.replaceAll("\\D", "");
            PiiType type = digits.length() >= 7 && digits.charAt(6) >= '5' && digits.charAt(6) <= '8'
                    ? PiiType.FOREIGNER_REGISTRATION_NUMBER
                    : PiiType.RESIDENT_REGISTRATION_NUMBER;
            String around = text.substring(Math.max(0, matcher.start() - 10), Math.min(text.length(), matcher.end() + 10));
            if (around.contains("외국인")) {
                type = PiiType.FOREIGNER_REGISTRATION_NUMBER;
            }
            matches.add(new PiiTextMatch(type,
                    type == PiiType.FOREIGNER_REGISTRATION_NUMBER ? "외국인등록번호" : "주민등록번호",
                    raw.trim(),
                    PiiMaskingRules.mask(type, raw.trim()),
                    matcher.start(),
                    matcher.end()));
        }
    }

    private static void addPassport(List<PiiTextMatch> matches, String text) {
        Matcher matcher = PASSPORT_LABEL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(2);
            matches.add(new PiiTextMatch(
                    PiiType.PASSPORT_NUMBER,
                    "여권번호",
                    raw,
                    PiiMaskingRules.mask(PiiType.PASSPORT_NUMBER, raw),
                    matcher.start(2),
                    matcher.end(2)
            ));
        }
    }

    private static void addLandline(List<PiiTextMatch> matches, String text) {
        Matcher matcher = LANDLINE_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw.replaceAll("\\s", "").startsWith("010")
                    || raw.replaceAll("\\s", "").startsWith("011")
                    || raw.replaceAll("\\s", "").startsWith("016")
                    || raw.replaceAll("\\s", "").startsWith("017")
                    || raw.replaceAll("\\s", "").startsWith("018")
                    || raw.replaceAll("\\s", "").startsWith("019")) {
                continue;
            }
            matches.add(new PiiTextMatch(
                    PiiType.LANDLINE_PHONE_NUMBER,
                    "전화번호",
                    raw.trim(),
                    PiiMaskingRules.mask(PiiType.LANDLINE_PHONE_NUMBER, raw.trim()),
                    matcher.start(),
                    matcher.end()
            ));
        }
    }

    private static void addCards(List<PiiTextMatch> matches, String text) {
        Matcher matcher = CARD_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String digits = raw.replaceAll("\\D", "");
            if (digits.length() != 16 || !passesLuhn(digits)) {
                continue;
            }
            matches.add(new PiiTextMatch(
                    PiiType.CREDIT_CARD_NUMBER,
                    "신용카드번호",
                    raw.trim(),
                    PiiMaskingRules.mask(PiiType.CREDIT_CARD_NUMBER, raw.trim()),
                    matcher.start(),
                    matcher.end()
            ));
        }
    }

    private static void addAccount(List<PiiTextMatch> matches, String text) {
        Matcher matcher = ACCOUNT_LABEL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(2).trim();
            matches.add(new PiiTextMatch(
                    PiiType.BANK_ACCOUNT_NUMBER,
                    "계좌번호",
                    raw,
                    PiiMaskingRules.mask(PiiType.BANK_ACCOUNT_NUMBER, raw),
                    matcher.start(2),
                    matcher.end(2)
            ));
        }
    }

    private static void addName(List<PiiTextMatch> matches, String text) {
        Matcher matcher = NAME_LABEL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(2);
            matches.add(new PiiTextMatch(
                    PiiType.PERSON_NAME,
                    "이름",
                    raw,
                    PiiMaskingRules.mask(PiiType.PERSON_NAME, raw),
                    matcher.start(2),
                    matcher.end(2)
            ));
        }
    }

    private static void addIp(List<PiiTextMatch> matches, String text) {
        Matcher matcher = IP_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String[] tokens = raw.split("\\.");
            boolean valid = true;
            for (String token : tokens) {
                int value = Integer.parseInt(token);
                if (value < 0 || value > 255) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                continue;
            }
            matches.add(new PiiTextMatch(
                    PiiType.IP_ADDRESS,
                    "IP주소",
                    raw,
                    PiiMaskingRules.mask(PiiType.IP_ADDRESS, raw),
                    matcher.start(),
                    matcher.end()
            ));
        }
    }

    private static void addAddress(List<PiiTextMatch> matches, String text) {
        Matcher matcher = ADDRESS_LABEL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(2).trim();
            if (!looksLikeStreetAddress(raw)) {
                continue;
            }
            matches.add(new PiiTextMatch(
                    PiiType.STREET_ADDRESS,
                    "주소",
                    raw,
                    PiiMaskingRules.mask(PiiType.STREET_ADDRESS, raw),
                    matcher.start(2),
                    matcher.end(2)
            ));
        }
    }

    private static void addRegex(List<PiiTextMatch> matches, String text, Pattern pattern, PiiType type, String label) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            matches.add(new PiiTextMatch(
                    type,
                    label,
                    raw.trim(),
                    PiiMaskingRules.mask(type, raw.trim()),
                    matcher.start(),
                    matcher.end()
            ));
        }
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private static boolean looksLikeStreetAddress(String raw) {
        if (raw.length() < 6) {
            return false;
        }
        String normalized = raw.replaceAll("\\s+", " ").trim();
        if (!normalized.matches(".*\\d+.*")) {
            return false;
        }
        if (!(normalized.contains("시 ") || normalized.contains("도 ")
                || normalized.contains("구 ") || normalized.contains("군 ")
                || normalized.contains("로 ") || normalized.contains("길 "))) {
            return false;
        }
        return normalized.split("\\s+").length >= 3;
    }
}
