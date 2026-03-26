package com.example.jadp.support;

import com.example.jadp.model.PiiType;

public record PiiTextMatch(
        PiiType type,
        String label,
        String originalText,
        String maskedText,
        int start,
        int end
) {
}
