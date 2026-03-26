package com.example.jadp;

import com.example.jadp.config.OpenDataLoaderProperties;
import com.example.jadp.config.StorageProperties;
import com.example.jadp.config.VllmOcrProperties;
import com.example.jadp.config.HybridProcessingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        StorageProperties.class,
        OpenDataLoaderProperties.class,
        HybridProcessingProperties.class,
        VllmOcrProperties.class
})
public class JadpApplication {

    public static void main(String[] args) {
        SpringApplication.run(JadpApplication.class, args);
    }
}
