package com.example.jadp.dto;

import java.util.List;

public record UpstageParseElement(
        String category,
        UpstageParseContent content,
        List<UpstageParseCoordinate> coordinates,
        int id,
        int page
) {
}
