package com.prince.turtlekeyboard.command;

import com.prince.turtlekeyboard.ai.AiClient;
import com.prince.turtlekeyboard.ai.AiResult;
import com.prince.turtlekeyboard.input.InputCommitter;

/**
 * Glue between detection and execution. When invoked: strips the slash invocation from the
 * field (PRD §6.4 cursor-aware insertion), calls the AiClient, then writes the result back.
 */
public class CommandDispatcher {

    public interface ResultUi {
        void showStatus(String message);
        void showSuggestions(String[] suggestions);
        void showImage(String imageUri);
        void clearStatus();
    }

    private final AiClient client;
    private final InputCommitter committer;
    private final ResultUi ui;

    public CommandDispatcher(AiClient client, InputCommitter committer, ResultUi ui) {
        this.client = client;
        this.committer = committer;
        this.ui = ui;
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
