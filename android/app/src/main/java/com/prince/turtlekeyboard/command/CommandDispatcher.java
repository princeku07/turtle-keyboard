package com.prince.turtlekeyboard.command;

import com.prince.kbd.core.IntegrationContext;
import com.prince.turtlekeyboard.ai.AiClient;
import com.prince.turtlekeyboard.ai.AiResult;
import com.prince.turtlekeyboard.input.InputCommitter;

/**
 * Glue between detection and execution. AI commands round-trip to {@link AiClient};
 * integration commands with a non-null handler run locally with an {@link IntegrationContext}.
 */
public class CommandDispatcher {

    public interface ResultUi {
        void showStatus(String message);
        void showSuggestions(String[] suggestions);
        void showImage(String imageUri);
        void clearStatus();
    }

    public interface ContextProvider {
        IntegrationContext get();
    }

    private final AiClient client;
    private final InputCommitter committer;
    private final ResultUi ui;
    private final CommandRegistry registry;
    private final ContextProvider contextProvider;

    public CommandDispatcher(AiClient client, InputCommitter committer, ResultUi ui,
                             CommandRegistry registry, ContextProvider contextProvider) {
        this.client = client;
        this.committer = committer;
        this.ui = ui;
        this.registry = registry;
        this.contextProvider = contextProvider;
    }

    public void dispatch(SlashCommand cmd) {
        // Strip the typed "/cmd args " from the field before showing the result.
        committer.deleteBeforeCursor(cmd.raw.length() + 1);
        run(cmd);
    }

    /** Dispatch a command composed in the keyboard UI that never reached the field. */
    public void dispatchComposed(SlashCommand cmd) {
        run(cmd);
    }

    private void run(SlashCommand cmd) {
        CommandRegistry.Entry e = registry.get(cmd.name);
        if (e != null && e.handler != null) {
            e.handler.handle(cmd.prompt == null ? "" : cmd.prompt, contextProvider.get());
            return;
        }
        // Trailing "…" is the loader marker: showStatus uses it to pick the loader vs transient banner.
        String base = (e != null && e.loadingMessage != null && !e.loadingMessage.isEmpty())
                ? e.loadingMessage
                : "/" + cmd.name;
        ui.showStatus(base + "…");
        client.execute(cmd, result -> handle(result));
    }

    private void handle(AiResult result) {
        switch (result.kind) {
            case TEXT:
                committer.commitText(result.text);
                ui.clearStatus();
                break;
            case SUGGESTIONS:
                ui.showSuggestions(result.suggestions);
                break;
            case IMAGE:
                ui.showImage(result.imageUri);
                break;
            case ERROR:
                ui.showStatus(result.error);
                break;
        }
    }
}
