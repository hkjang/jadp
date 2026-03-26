package com.example.jadp.service;

import com.example.jadp.model.PdfConversionOptions;

import java.nio.file.Path;

public interface PdfConversionEngine {

    void convert(Path inputFile, Path outputDirectory, PdfConversionOptions options);

    boolean isAvailable();

    String getEngineName();

    String getAvailabilityMessage();
}

