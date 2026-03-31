package com.example.jadp.service;

import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.support.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class ReflectiveOpenDataLoaderEngine implements PdfConversionEngine, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReflectiveOpenDataLoaderEngine.class);
    private static final String CONFIG_CLASS_NAME = "org.opendataloader.pdf.api.Config";
    private static final String ENGINE_CLASS_NAME = "org.opendataloader.pdf.api.OpenDataLoaderPDF";

    private volatile Boolean available;
    private volatile Throwable availabilityError;

    @Override
    public void convert(Path inputFile, Path outputDirectory, PdfConversionOptions options) {
        ensureAvailability();
        if (!Boolean.TRUE.equals(available)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, getAvailabilityMessage(), availabilityError);
        }

        try {
            Class<?> configClass = Class.forName(CONFIG_CLASS_NAME);
            Object config = configClass.getDeclaredConstructor().newInstance();

            setIfPresent(config, List.of("setOutputFolder", "setOutputDir", "setOutputDirectory"), outputDirectory.toString());
            setIfPresent(config, List.of("setPassword"), options.password());
            setIfPresent(config, List.of("setPages"), options.pages());
            setIfPresent(config, List.of("setKeepLineBreaks"), options.keepLineBreaks());
            setIfPresent(config, List.of("setUseStructTree"), options.useStructTree());
            setIfPresent(config, List.of("setReadingOrder"), options.readingOrder());
            setIfPresent(config, List.of("setTableMethod"), options.tableMethod());
            setIfPresent(config, List.of("setImageOutput"), options.imageOutput());
            setIfPresent(config, List.of("setImageFormat"), options.imageFormat());
            setIfPresent(config, List.of("setImageDir"), options.imageDir());
            setIfPresent(config, List.of("setIncludeHeaderFooter"), options.includeHeaderFooter());
            setIfPresent(config, List.of("setHybrid"), options.hybrid());
            setIfPresent(config, List.of("setHybridMode"), options.hybridMode());
            setIfPresent(config, List.of("setHybridUrl"), options.hybridUrl());
            setIfPresent(config, List.of("setHybridTimeout"), options.hybridTimeout());
            setIfPresent(config, List.of("setHybridFallback"), options.hybridFallback());

            if (Boolean.FALSE.equals(options.sanitize())) {
                setIfPresent(config, List.of("setContentSafetyOff"), "sensitive-data");
            }

            String formatsCsv = String.join(",", options.formats());
            setIfPresent(config, List.of("setFormat", "setFormats", "setOutputFormat", "setOutputFormats"), formatsCsv);
            setIfPresent(config, List.of("setGenerateJson", "setGenerateJSON"), options.formats().contains("json"));
            setIfPresent(config, List.of("setGenerateText"), options.formats().contains("text"));
            setIfPresent(config, List.of("setGenerateHtml"), options.formats().contains("html"));
            setIfPresent(config, List.of("setGeneratePDF", "setGeneratePdf"), options.formats().contains("pdf"));
            setIfPresent(config, List.of("setGenerateMarkdown"), containsAny(options.formats(), "markdown", "markdown-with-html", "markdown-with-images"));
            setIfPresent(config, List.of("setGenerateMarkdownWithHtml", "setUseHTMLInMarkdown"), options.formats().contains("markdown-with-html"));
            setIfPresent(config, List.of("setGenerateMarkdownWithImages", "setAddImageToMarkdown"), options.formats().contains("markdown-with-images"));

            Class<?> engineClass = Class.forName(ENGINE_CLASS_NAME);
            Method processFile = engineClass.getMethod("processFile", String.class, configClass);
            processFile.invoke(null, inputFile.toString(), config);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException() == null ? ex : ex.getTargetException();
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "OpenDataLoader PDF conversion failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenDataLoader PDF API invocation failed. Review the pinned library version.", ex);
        }
    }

    @Override
    public boolean isAvailable() {
        ensureAvailability();
        return Boolean.TRUE.equals(available);
    }

    @Override
    public String getEngineName() {
        return "OpenDataLoaderPDF(reflection)";
    }

    @Override
    public String getAvailabilityMessage() {
        if (Boolean.TRUE.equals(available)) {
            return "OpenDataLoader PDF classes available";
        }
        if (availabilityError == null) {
            return "OpenDataLoader PDF classes not checked yet";
        }
        return "OpenDataLoader PDF classes unavailable: " + availabilityError.getMessage();
    }

    @Override
    public void destroy() {
        if (!isAvailable()) {
            return;
        }
        try {
            Class<?> engineClass = Class.forName(ENGINE_CLASS_NAME);
            Method shutdown = engineClass.getMethod("shutdown");
            shutdown.invoke(null);
        } catch (Exception ex) {
            log.warn("OpenDataLoader shutdown invocation failed: {}", ex.getMessage());
        }
    }

    private void ensureAvailability() {
        if (available != null) {
            return;
        }
        synchronized (this) {
            if (available != null) {
                return;
            }
            try {
                Class.forName(CONFIG_CLASS_NAME);
                Class.forName(ENGINE_CLASS_NAME);
                available = true;
                availabilityError = null;
            } catch (Throwable ex) {
                available = false;
                availabilityError = ex;
                log.error("[ENGINE] OpenDataLoader classes NOT found on classpath: {}", ex.getMessage(), ex);
            }
        }
    }

    private boolean containsAny(List<String> values, String... targets) {
        return Arrays.stream(targets).anyMatch(values::contains);
    }

    private void setIfPresent(Object target, List<String> methodNames, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String stringValue && stringValue.isBlank()) {
            return;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!methodNames.contains(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Object converted = coerce(value, method.getParameterTypes()[0]);
            if (converted == NoValue.INSTANCE) {
                continue;
            }
            try {
                method.invoke(target, converted);
                return;
            } catch (IllegalAccessException | InvocationTargetException ex) {
                log.debug("Setter {} rejected value {}: {}", method.getName(), value, ex.getMessage());
            }
        }
    }

    private Object coerce(Object value, Class<?> targetType) {
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(Objects::toString).reduce((a, b) -> a + "," + b).orElse("");
            }
            return value.toString();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            return Boolean.parseBoolean(value.toString());
        }
        if (targetType == int.class || targetType == Integer.class) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(value.toString());
        }
        if (targetType == long.class || targetType == Long.class) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Long.parseLong(value.toString());
        }
        if (targetType.isEnum()) {
            String token = value.toString().trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
            Object[] constants = targetType.getEnumConstants();
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(token)) {
                    return constant;
                }
            }
            return NoValue.INSTANCE;
        }
        if (targetType.isArray()) {
            List<String> tokens;
            if (value instanceof Collection<?> collection) {
                tokens = collection.stream().map(Objects::toString).toList();
            } else {
                tokens = Arrays.stream(value.toString().split(",")).map(String::trim).toList();
            }
            Object array = Array.newInstance(targetType.getComponentType(), tokens.size());
            for (int i = 0; i < tokens.size(); i++) {
                Array.set(array, i, tokens.get(i));
            }
            return array;
        }
        if (Collection.class.isAssignableFrom(targetType)) {
            if (value instanceof Collection<?> collection) {
                return List.copyOf(collection);
            }
            return Arrays.stream(value.toString().split(","))
                    .map(String::trim)
                    .filter(token -> !token.isBlank())
                    .toList();
        }
        return NoValue.INSTANCE;
    }

    private enum NoValue {
        INSTANCE
    }
}
