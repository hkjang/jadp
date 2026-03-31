package com.example.jadp.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FileNameSanitizerTest {

    @Test
    void preservesKoreanFilename() {
        assertThat(FileNameSanitizer.sanitize("한글문서.pdf")).isEqualTo("한글문서.pdf");
    }

    @Test
    void preservesKoreanWithSpaces() {
        assertThat(FileNameSanitizer.sanitize("한글 문서 테스트.pdf")).isEqualTo("한글_문서_테스트.pdf");
    }

    @Test
    void preservesCjkMixedFilename() {
        assertThat(FileNameSanitizer.sanitize("日本語テスト.pdf")).isEqualTo("日本語テスト.pdf");
    }

    @Test
    void preservesLatinFilename() {
        assertThat(FileNameSanitizer.sanitize("document.pdf")).isEqualTo("document.pdf");
    }

    @Test
    void replacesSpecialCharacters() {
        String result = FileNameSanitizer.sanitize("file<>name.pdf");
        assertThat(result).isEqualTo("file_name.pdf");
    }

    @Test
    void collapsesConsecutiveUnderscores() {
        String result = FileNameSanitizer.sanitize("a***b.pdf");
        assertThat(result).isEqualTo("a_b.pdf");
    }

    @Test
    void trimsLeadingAndTrailingUnderscores() {
        String result = FileNameSanitizer.sanitize("___test___.pdf");
        assertThat(result).isEqualTo("test.pdf");
    }

    @Test
    void addsPdfExtensionWhenMissing() {
        assertThat(FileNameSanitizer.sanitize("document")).isEqualTo("document.pdf");
    }

    @Test
    void addsPdfExtensionForUnknownExtension() {
        assertThat(FileNameSanitizer.sanitize("file.docx")).isEqualTo("file.pdf");
    }

    @ParameterizedTest
    @CsvSource({
            "image.png, image.png",
            "photo.jpg, photo.jpg",
            "scan.jpeg, scan.jpeg",
            "IMAGE.PNG, IMAGE.PNG",
            "Photo.JPG, Photo.JPG"
    })
    void preservesAllowedImageExtensions(String input, String expected) {
        assertThat(FileNameSanitizer.sanitize(input)).isEqualTo(expected);
    }

    @Test
    void fallsBackToDocumentWhenStemIsEmpty() {
        assertThat(FileNameSanitizer.sanitize("!!!.pdf")).isEqualTo("document.pdf");
    }

    @Test
    void fallsBackToDocumentWhenAllSpecialChars() {
        assertThat(FileNameSanitizer.sanitize("$$$###")).isEqualTo("document.pdf");
    }

    @Test
    void handlesHyphenAndDot() {
        assertThat(FileNameSanitizer.sanitize("my-file.v2.pdf")).isEqualTo("my-file.pdf");
    }

    @Test
    void handlesDigitsInFilename() {
        assertThat(FileNameSanitizer.sanitize("report_2024_01.pdf")).isEqualTo("report_2024_01.pdf");
    }

    @Test
    void handlesKoreanPngUpload() {
        assertThat(FileNameSanitizer.sanitize("한글테스트문서.png")).isEqualTo("한글테스트문서.png");
    }

    @Test
    void handlesMixedKoreanAndLatinWithSpecialChars() {
        String result = FileNameSanitizer.sanitize("보고서(2024)_final.pdf");
        assertThat(result)
                .contains("보고서")
                .contains("final")
                .endsWith(".pdf");
    }
}
