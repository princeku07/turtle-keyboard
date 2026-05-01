package com.prince.turtlekeyboard.command;

import com.prince.split.kbd.IntegrationContext;
import com.prince.turtlekeyboard.ai.AiClient;
import com.prince.turtlekeyboard.ai.AiResult;
import com.prince.turtlekeyboard.input.InputCommitter;

/**
 * Glue between detection and execution. Two paths:
 *
 * <ul>
 *   <li>Built-in AI commands round-trip to the {@link AiClient} and the result is written
 *       back into the host editor.</li>
 *   <li>Integration-contributed commands (with a non-null handler) run locally — the
 *       handler receives the parsed prompt and the live {@link IntegrationContext}.</li>
 * </ul>
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
        // Remove the typed "/cmd args " from the field before showing the result.
        committer.deleteBeforeCursor(cmd.raw.length() + 1);
        run(cmd);
    }

    /** Dispatch a command that was composed in the keyboard UI and never reached the field. */
    public void dispatchComposed(SlashCommand cmd) {
        run(cmd);
    }

    private void run(SlashCommand cmd) {
        CommandRegistry.Entry e = registry.get(cmd.name);
        if (e != null && e.handler != null) {
            // Local handler — runs synchronously, no AI round trip.
            e.handler.handle(cmd.prompt == null ? "" : cmd.prompt, contextProvider.get());
            return;
        }
        ui.showStatus("/" + cmd.name + "…");
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
