package com.example.jadp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("swaggerUrl", "/swagger-ui.html");
        model.addAttribute("healthUrl", "/actuator/health");
        model.addAttribute("docsUrl", "/docs");
        return "index";
    }

    @GetMapping("/docs")
    public String docs(Model model) {
        model.addAttribute("swaggerUrl", "/swagger-ui.html");
        model.addAttribute("apiDocsUrl", "/v3/api-docs");
        model.addAttribute("testPageUrl", "/");
        return "docs";
    }
}
