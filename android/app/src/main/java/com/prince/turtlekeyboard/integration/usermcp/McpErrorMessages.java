package com.prince.turtlekeyboard.integration.usermcp;

import com.prince.kbd.core.McpService;

/**
 * Maps {@link McpService} raw failure reasons to short banner messages prefixed with
 * a caller-supplied subject. Call sites should still log the raw reason.
 */
public final class McpErrorMessages {

    private McpErrorMessages() {}

    public static String userMessage(String reason, String subject) {
        String prefix = (subject == null || subject.isEmpty() ? "Request" : subject) + " ";
        if (reason == null) return prefix + "failed — try again";
        String r = reason.trim();
        if (r.isEmpty()) return prefix + "failed — try again";

        if (r.contains("UnknownHostException")
                || r.contains("ConnectException")
                || r.contains("SocketException")) {
            return prefix + "couldn't reach server — check your internet";
        }
        if (r.contains("SocketTimeoutException") || r.toLowerCase().contains("timeout")) {
            return prefix + "timed out — try again";
        }
        if (r.contains("SSLHandshakeException") || r.contains("SSLException")) {
            return prefix + "secure connection failed";
        }
        if (r.startsWith("http_401") || r.startsWith("http_403")) {
            return prefix + "rejected — re-auth the server";
        }
        if (r.startsWith("http_404")) {
            return prefix + "server not found — check the URL";
        }
        if (r.startsWith("http_429")) {
            return prefix + "rate limited — wait a moment";
        }
        if (r.startsWith("http_4")) {
            return prefix + "rejected — check your binding";
        }
        if (r.startsWith("http_5")) {
            return prefix + "server error — try again";
        }
        if (r.startsWith("http_")) {
            return prefix + "failed — try again";
        }
        if ("mcp_no_result".equals(r)) {
            return prefix + "got no result — check your binding";
        }
        if ("mcp_tool_error".equals(r)) {
            return prefix + "tool reported an error — check your args";
        }
        if (r.startsWith("mcp_")) {
            String hint = r.substring("mcp_".length()).trim();
            if (hint.isEmpty()) return prefix + "failed — try again";
            if (hint.length() > 60) hint = hint.substring(0, 57) + "…";
            return prefix + "failed: " + hint;
        }
        return prefix + "failed — try again";
    }
}
