package com.prince.turtlekeyboard.ai;

import com.prince.kbd.core.GeminiService;

/**
 * Translates raw {@link GeminiService} error reasons into short banner-sized
 * user-facing messages. Callers should still log the raw reason for diagnostics.
 */
public final class AiErrorMessages {

    private AiErrorMessages() {}

    public static String userMessage(String reason) {
        if (reason == null) return "Something went wrong — try again";
        String r = reason.trim();
        if (r.isEmpty()) return "Something went wrong — try again";

        if ("ai_no_api_key".equals(r)) {
            return "AI key missing — set GEMINI_API_KEY";
        }
        if (r.startsWith("model refused")) {
            return "Model declined — try rephrasing";
        }
        if (r.contains("UnknownHostException")
                || r.contains("ConnectException")
                || r.contains("SocketException")) {
            return "No connection — check your internet";
        }
        if (r.contains("SocketTimeoutException") || r.toLowerCase().contains("timeout")) {
            return "AI timed out — try again";
        }
        if (r.contains("SSLHandshakeException") || r.contains("SSLException")) {
            return "Secure connection failed — try again";
        }
        if (r.startsWith("HTTP 401") || r.startsWith("HTTP 403")) {
            return "AI key rejected — check your key";
        }
        if (r.startsWith("HTTP 429")) {
            return "Rate limited — give it a minute";
        }
        if (r.startsWith("HTTP 400")) {
            return "AI rejected the request — try rephrasing";
        }
        if (r.startsWith("HTTP 5")) {
            return "AI service is having a moment — try again";
        }
        if (r.startsWith("HTTP ")) {
            return "AI request failed — try again";
        }
        if (r.startsWith("no candidates")
                || r.startsWith("no content")
                || r.startsWith("no parts")
                || r.startsWith("no image part")) {
            return "AI returned nothing — try rephrasing";
        }
        return "AI request failed — try again";
    }
}
