package com.example.jadp.dto;

import java.util.List;

public record JobListResponse(
        int page,
        int size,
        List<JobDetailResponse> jobs
) {
}

