package com.example.jadp.controller;

import com.example.jadp.support.ApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTest.TestExceptionController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @RestController
    static class TestExceptionController {

        @GetMapping("/test/api-exception-400")
        String apiBadRequest() {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing required field");
        }

        @GetMapping("/test/api-exception-503")
        String apiServiceUnavailable() {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Engine not available");
        }

        @GetMapping("/test/api-exception-422")
        String apiUnprocessable() {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF conversion failed");
        }

        @GetMapping("/test/runtime-exception")
        String runtimeException() {
            throw new RuntimeException("Unexpected internal error");
        }

        @GetMapping("/test/null-message-exception")
        String nullMessageException() {
            throw new RuntimeException();
        }

        @GetMapping("/test/max-upload")
        String maxUpload() {
            throw new MaxUploadSizeExceededException(25 * 1024 * 1024);
        }

        @GetMapping("/test/constraint-violation")
        String constraintViolation() {
            Set<ConstraintViolation<?>> violations = Set.of();
            throw new ConstraintViolationException("Validation failed", violations);
        }
    }

    @Test
    void apiException400ReturnsCorrectStructure() throws Exception {
        mockMvc.perform(get("/test/api-exception-400").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Missing required field"))
                .andExpect(jsonPath("$.path").value("/test/api-exception-400"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void apiException503ReturnsServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/api-exception-503").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Engine not available"));
    }

    @Test
    void apiException422ReturnsUnprocessableEntity() throws Exception {
        mockMvc.perform(get("/test/api-exception-422").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("PDF conversion failed"));
    }

    @Test
    void runtimeExceptionReturns500WithMessage() throws Exception {
        mockMvc.perform(get("/test/runtime-exception").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("Unexpected internal error"));
    }

    @Test
    void nullMessageExceptionReturns500() throws Exception {
        mockMvc.perform(get("/test/null-message-exception").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    void maxUploadSizeReturns413() throws Exception {
        mockMvc.perform(get("/test/max-upload").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.message").value("Uploaded file exceeds the configured size limit."));
    }

    @Test
    void constraintViolationReturns400() throws Exception {
        mockMvc.perform(get("/test/constraint-violation").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
