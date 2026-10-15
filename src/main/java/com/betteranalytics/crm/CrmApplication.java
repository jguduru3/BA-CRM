package com.betteranalytics.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CrmApplication {

    public static void main(String[] args) {
        attachApplicationInsightsIfConfigured();
        SpringApplication.run(CrmApplication.class, args);
    }

    /**
     * Azure Application Insights (requests, dependencies, exceptions, logs) is
     * attached only when APPLICATIONINSIGHTS_CONNECTION_STRING is set, so local
     * runs and tests never load the agent. Must run before Spring starts.
     */
    static boolean attachApplicationInsightsIfConfigured() {
        String cs = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");
        if (cs == null || cs.isBlank()) return false;
        try {
            com.microsoft.applicationinsights.attach.ApplicationInsights.attach();
            return true;
        } catch (Throwable t) {
            // Telemetry must never stop the API from starting.
            System.err.println("Application Insights could not be attached: " + t);
            return false;
        }
    }
}
