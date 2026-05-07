package com.prince.notion;

/** Keys the Notion module persists into its scoped {@code ctx.store("notion")} view. */
public final class NotionKeys {
    public static final String ACCESS_TOKEN     = "access_token";
    public static final String WORKSPACE_NAME   = "workspace_name";
    public static final String DEFAULT_PARENT   = "default_parent_id";
    public static final String DEFAULT_PARENT_T = "default_parent_title";
    public static final String ENABLED          = "enabled";

    private NotionKeys() {}
}
