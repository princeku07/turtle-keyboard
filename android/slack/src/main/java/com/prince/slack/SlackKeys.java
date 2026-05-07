package com.prince.slack;

/** Keys the Slack module persists into its scoped {@code ctx.store("slack")} view. */
public final class SlackKeys {
    public static final String ACCESS_TOKEN         = "access_token";
    public static final String TEAM_NAME            = "team_name";
    public static final String TEAM_DOMAIN          = "team_domain";
    public static final String DEFAULT_CHANNEL      = "default_channel_id";
    public static final String DEFAULT_CHANNEL_NAME = "default_channel_name";
    public static final String ENABLED              = "enabled";

    private SlackKeys() {}
}
