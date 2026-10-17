package com.example.store.bdd;

import com.example.store.integration.TestcontainersConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import io.cucumber.spring.CucumberContextConfiguration;

// Must match AbstractIntegrationTest exactly so Cucumber shares the same Spring context and database.
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public class CucumberSpringConfiguration {}
