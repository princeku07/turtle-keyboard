package com.prince.notion;

/** Namespaced storage keys used by the Notion module against the shared SplitStore. */
public final class NotionKeys {
    public static final String ACCESS_TOKEN     = "notion.access_token";
    public static final String WORKSPACE_NAME   = "notion.workspace_name";
    public static final String DEFAULT_PARENT   = "notion.default_parent_id";
    public static final String DEFAULT_PARENT_T = "notion.default_parent_title";
    /** Master toggle. */
    public static final String ENABLED          = "notion.enabled";

    private NotionKeys() {}
}
