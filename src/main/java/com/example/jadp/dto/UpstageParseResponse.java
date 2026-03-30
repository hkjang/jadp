package com.example.jadp.dto;

import java.util.List;

public record UpstageParseResponse(
        String api,
        UpstageParseContent content,
        List<UpstageParseElement> elements,
        String model,
        UpstageParseUsage usage
) {
}
