package com.testinsight.aihealer;

import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;

import lombok.Getter;
import okhttp3.OkHttpClient;

/**
 * Base class for AI-powered functionality.
 * Provides common configuration and utilities for AI services.
 */
public class AIBase {

    // AI Analysis Constants
    @Getter
    protected static String AI_API_KEY = System.getenv("AI_API_KEY");
    protected static String AI_CHAT_MODEL = "gpt-4o";
    protected static String AI_VISION_MODEL = "gpt-4-vision-preview";
    protected static boolean IS_AI_ANALYSIS_REQUIRED = false;
    protected static final Gson gson = new Gson();
    protected static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    
    protected static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * Enable AI analysis functionality.
     * Checks the AI_ANALYSIS_ENABLED environment variable.
     */
    public static void setAI_ANALYSIS_ENABLED() {
        String enabled = System.getenv("AI_ANALYSIS_ENABLED");
        if (enabled != null && enabled.equalsIgnoreCase("true")) {
            IS_AI_ANALYSIS_REQUIRED = true;
        }
    }
    
    /**
     * Check if AI analysis is enabled.
     * @return true if AI analysis is enabled, false otherwise
     */
    public static boolean getAI_ANALYSIS_ENABLED() {
        String enabled = System.getenv("AI_ANALYSIS_ENABLED");
        if (enabled != null && enabled.equalsIgnoreCase("true")) {
            IS_AI_ANALYSIS_REQUIRED = true;
        }
        return IS_AI_ANALYSIS_REQUIRED;
    }

    /**
     * Get the configured AI model name.
     * @return the AI model name
     */
    public static String getAI_MODEL() {
        return AI_CHAT_MODEL;
    }
    
    /**
     * Set the AI chat model to use.
     * @param model the model name (e.g., "gpt-4o", "gpt-4-turbo")
     */
    public static void setAI_CHAT_MODEL(String model) {
        if (model != null && !model.trim().isEmpty()) {
            AI_CHAT_MODEL = model;
        }
    }
    
    /**
     * Set the AI vision model to use.
     * @param model the model name
     */
    public static void setAI_VISION_MODEL(String model) {
        if (model != null && !model.trim().isEmpty()) {
            AI_VISION_MODEL = model;
        }
    }
}