package com.example.jadp.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ViewController.class)
class ViewControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void upstagePageIsExposedWithNavigationLinks() throws Exception {
        mockMvc.perform(get("/upstage"))
                .andExpect(status().isOk())
                .andExpect(view().name("upstage"))
                .andExpect(model().attribute("upstageUrl", "/upstage"))
                .andExpect(model().attribute("homeUrl", "/"))
                .andExpect(model().attribute("swaggerUrl", "/swagger-ui.html"));
    }
}
