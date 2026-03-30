package com.example.jadp.support;

import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PiiFindingMergeSupport {

    private PiiFindingMergeSupport() {
    }

    public static PiiDetectionResult mergeDetectionResult(PiiDetectionResult baseline, List<PiiFinding> additionalFindings) {
        return new PiiDetectionResult(
                baseline.documentId(),
                baseline.originalFilename(),
                baseline.contentType(),
                baseline.mediaType(),
                baseline.pageCount(),
                baseline.sourceFile(),
                mergeFindings(baseline.findings(), additionalFindings)
        );
    }

    public static List<PiiFinding> mergeFindings(List<PiiFinding> baseline, List<PiiFinding> additionalFindings) {
        List<PiiFinding> merged = new ArrayList<>(baseline);
        for (PiiFinding candidate : additionalFindings) {
            if (merged.stream().noneMatch(existing -> isSameFinding(existing, candidate))) {
                merged.add(candidate);
            }
        }
        merged.sort(Comparator.comparingInt(PiiFinding::pageNumber)
                .thenComparing(finding -> finding.boundingBox().y())
                .thenComparing(finding -> finding.boundingBox().x()));
        return List.copyOf(merged);
    }

    public static boolean isSameFinding(PiiFinding left, PiiFinding right) {
        if (left.pageNumber() != right.pageNumber() || left.type() != right.type()) {
            return false;
        }
        if (!normalize(left.originalText()).equals(normalize(right.originalText()))) {
            return false;
        }
        return intersectionOverUnion(left.boundingBox(), right.boundingBox()) >= 0.35d
                || distance(left.boundingBox().x(), left.boundingBox().y(), right.boundingBox().x(), right.boundingBox().y()) <= 18d;
    }

    private static double intersectionOverUnion(PiiBoundingBox left, PiiBoundingBox right) {
        double x1 = Math.max(left.x(), right.x());
        double y1 = Math.max(left.y(), right.y());
        double x2 = Math.min(left.x() + left.width(), right.x() + right.width());
        double y2 = Math.min(left.y() + left.height(), right.y() + right.height());
        double intersection = Math.max(0d, x2 - x1) * Math.max(0d, y2 - y1);
        if (intersection <= 0d) {
            return 0d;
        }
        double union = left.width() * left.height() + right.width() * right.height() - intersection;
        return union <= 0d ? 0d : intersection / union;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }
}
