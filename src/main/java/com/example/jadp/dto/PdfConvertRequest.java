package com.example.jadp.dto;

import com.example.jadp.model.PdfConversionOptions;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Schema(name = "PdfConvertRequest", description = "Multipart PDF conversion request")
public class PdfConvertRequest {

    @NotNull(message = "file is required")
    @Schema(type = "string", format = "binary", description = "PDF file to upload")
    private MultipartFile file;

    @ArraySchema(schema = @Schema(example = "markdown"))
    private List<String> formats = new ArrayList<>(List.of("json", "markdown", "html"));

    @Schema(example = "secret")
    private String password;

    @Schema(example = "1,3,5-7")
    private String pages;

    @Schema(example = "false")
    private Boolean keepLineBreaks = false;

    @Schema(example = "false")
    private Boolean useStructTree = false;

    @Schema(example = "xycut")
    private String readingOrder = "xycut";

    @Schema(example = "default")
    private String tableMethod = "default";

    @Schema(example = "external")
    private String imageOutput = "external";

    @Schema(example = "png")
    private String imageFormat = "png";

    @Schema(example = "var/app-data/custom-images")
    private String imageDir;

    @Schema(example = "false")
    private Boolean includeHeaderFooter = false;

    @Schema(example = "true")
    private Boolean sanitize = true;

    @Schema(example = "off")
    private String hybrid;

    @Schema(example = "auto")
    private String hybridMode;

    @Schema(example = "http://localhost:8088")
    private String hybridUrl;

    @Schema(example = "30000")
    private Long hybridTimeout;

    @Schema(example = "true")
    private Boolean hybridFallback;

    public PdfConversionOptions toOptions() {
        return new PdfConversionOptions(
                formats,
                password,
                pages,
                keepLineBreaks,
                useStructTree,
                readingOrder,
                tableMethod,
                imageOutput,
                imageFormat,
                imageDir,
                includeHeaderFooter,
                sanitize,
                hybrid,
                hybridMode,
                hybridUrl,
                hybridTimeout,
                hybridFallback
        );
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }

    public List<String> getFormats() {
        return formats;
    }

    public void setFormats(List<String> formats) {
        this.formats = formats;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPages() {
        return pages;
    }

    public void setPages(String pages) {
        this.pages = pages;
    }

    public Boolean getKeepLineBreaks() {
        return keepLineBreaks;
    }

    public void setKeepLineBreaks(Boolean keepLineBreaks) {
        this.keepLineBreaks = keepLineBreaks;
    }

    public Boolean getUseStructTree() {
        return useStructTree;
    }

    public void setUseStructTree(Boolean useStructTree) {
        this.useStructTree = useStructTree;
    }

    public String getReadingOrder() {
        return readingOrder;
    }

    public void setReadingOrder(String readingOrder) {
        this.readingOrder = readingOrder;
    }

    public String getTableMethod() {
        return tableMethod;
    }

    public void setTableMethod(String tableMethod) {
        this.tableMethod = tableMethod;
    }

    public String getImageOutput() {
        return imageOutput;
    }

    public void setImageOutput(String imageOutput) {
        this.imageOutput = imageOutput;
    }

    public String getImageFormat() {
        return imageFormat;
    }

    public void setImageFormat(String imageFormat) {
        this.imageFormat = imageFormat;
    }

    public String getImageDir() {
        return imageDir;
    }

    public void setImageDir(String imageDir) {
        this.imageDir = imageDir;
    }

    public Boolean getIncludeHeaderFooter() {
        return includeHeaderFooter;
    }

    public void setIncludeHeaderFooter(Boolean includeHeaderFooter) {
        this.includeHeaderFooter = includeHeaderFooter;
    }

    public Boolean getSanitize() {
        return sanitize;
    }

    public void setSanitize(Boolean sanitize) {
        this.sanitize = sanitize;
    }

    public String getHybrid() {
        return hybrid;
    }

    public void setHybrid(String hybrid) {
        this.hybrid = hybrid;
    }

    public String getHybridMode() {
        return hybridMode;
    }

    public void setHybridMode(String hybridMode) {
        this.hybridMode = hybridMode;
    }

    public String getHybridUrl() {
        return hybridUrl;
    }

    public void setHybridUrl(String hybridUrl) {
        this.hybridUrl = hybridUrl;
    }

    public Long getHybridTimeout() {
        return hybridTimeout;
    }

    public void setHybridTimeout(Long hybridTimeout) {
        this.hybridTimeout = hybridTimeout;
    }

    public Boolean getHybridFallback() {
        return hybridFallback;
    }

    public void setHybridFallback(Boolean hybridFallback) {
        this.hybridFallback = hybridFallback;
    }
}
