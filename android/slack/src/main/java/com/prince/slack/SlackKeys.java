package com.prince.slack;

/** Namespaced storage keys used by the Slack module against the shared SplitStore. */
public final class SlackKeys {
    public static final String ACCESS_TOKEN     = "slack.access_token";
    public static final String TEAM_NAME        = "slack.team_name";
    public static final String TEAM_DOMAIN      = "slack.team_domain";
    public static final String DEFAULT_CHANNEL  = "slack.default_channel_id";
    public static final String DEFAULT_CHANNEL_NAME = "slack.default_channel_name";
    /** Master toggle. */
    public static final String ENABLED          = "slack.enabled";

    private SlackKeys() {}
}
