package com.example.jadp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI jadpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("JADP OpenDataLoader PDF API")
                        .version("v1")
                        .description("PDF upload, async job execution, sync preview, download, and test UI for OpenDataLoader PDF."));
    }
}

