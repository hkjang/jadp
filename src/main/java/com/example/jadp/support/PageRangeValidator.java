package com.example.jadp.support;

import java.util.regex.Pattern;

public final class PageRangeValidator {

    private static final Pattern PAGE_RANGE_PATTERN = Pattern.compile("^\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*$");

    private PageRangeValidator() {
    }

    public static boolean isValid(String pageRange) {
        return PAGE_RANGE_PATTERN.matcher(pageRange.replace(" ", "")).matches();
    }
}
