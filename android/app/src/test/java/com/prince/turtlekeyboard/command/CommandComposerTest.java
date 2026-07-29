package com.prince.turtlekeyboard.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class CommandComposerTest {

    /** Recording UI that captures the last callback fired and how many times each fired. */
    private static final class RecordingUi implements CommandComposer.Ui {
        String lastName, lastQuery, promptCmd;
        int cursorPos;
        int nameChangedCount, promptStartCount, promptChangedCount, endCount;
        boolean composeEnded;

        @Override public void onNameChanged(String displayed) {
            lastName = displayed;
            nameChangedCount++;
        }
        @Override public void onPromptStart(String commandName) {
            promptCmd = commandName;
            promptStartCount++;
        }
        @Override public void onPromptChanged(String cmd, String query, int cursor) {
            promptCmd = cmd;
            lastQuery = query;
            cursorPos = cursor;
            promptChangedCount++;
        }
        @Override public void onComposeEnd() {
            composeEnded = true;
            endCount++;
        }
    }

    private RecordingUi ui;
    private CommandComposer composer;

    @Before
    public void setUp() {
        ui = new RecordingUi();
        composer = new CommandComposer(ui);
    }

    @Test
    public void isActive_false_until_start() {
        assertFalse(composer.isActive());
        composer.startName();
        assertTrue(composer.isActive());
        assertEquals(CommandComposer.Mode.NAME, composer.mode());
    }

    @Test
    public void name_mode_appends_chars_and_fires_callback() {
        composer.startName();
        composer.appendChar('c');
        composer.appendChar('a');
        composer.appendChar('p');
        assertEquals("/cap", ui.lastName);
        assertEquals("/cap", composer.nameText());
    }

    @Test
    public void prompt_mode_starts_with_empty_buffer_and_cursor_zero() {
        composer.startName();
        composer.enterPromptMode("cap");
        assertEquals(CommandComposer.Mode.PROMPT, composer.mode());
        assertEquals("cap", composer.commandName());
        assertEquals("", composer.query());
        assertEquals(0, composer.promptCursor());
        assertEquals(1, ui.promptStartCount);
    }

    @Test
    public void prompt_append_advances_cursor() {
        composer.startName();
        composer.enterPromptMode("cap");
        composer.appendChar('h');
        composer.appendChar('i');
        assertEquals("hi", composer.query());
        assertEquals(2, composer.promptCursor());
    }

    @Test
    public void prompt_insert_at_cursor_position() {
        composer.startName();
        composer.enterPromptMode("cap");
        composer.appendString("hello world");
        composer.setPromptCursor(5);
        composer.appendChar('!');
        assertEquals("hello! world", composer.query());
        assertEquals(6, composer.promptCursor());
    }

    @Test
    public void backspace_in_name_with_only_slash_cancels() {
        composer.startName();
        // Buffer is "/" after startName; backspace cancels.
        assertTrue(composer.backspace());
        assertFalse(composer.isActive());
        assertTrue(ui.composeEnded);
    }

    @Test
    public void backspace_in_name_deletes_one_char() {
        composer.startName();
        composer.appendChar('c');
        composer.appendChar('a');
        composer.backspace();
        assertEquals("/c", composer.nameText());
        assertTrue(composer.isActive());
    }

    @Test
    public void backspace_in_prompt_with_empty_buffer_cancels() {
        composer.startName();
        composer.enterPromptMode("cap");
        assertTrue(composer.backspace());
        assertFalse(composer.isActive());
    }

    @Test
    public void backspace_in_prompt_at_cursor_zero_is_noop_when_buffer_nonempty() {
        composer.startName();
        composer.enterPromptMode("cap");
        composer.appendString("hello");
        composer.setPromptCursor(0);
        int beforeChanged = ui.promptChangedCount;
        // Cursor at start with text: should NOT delete anything (would lose typed prefix).
        assertTrue(composer.backspace());
        assertEquals("hello", composer.query());
        assertEquals(0, composer.promptCursor());
        // No prompt-change callback fired for the no-op.
        assertEquals(beforeChanged, ui.promptChangedCount);
    }

    @Test
    public void setPromptCursor_clamps_to_buffer_bounds() {
        composer.startName();
        composer.enterPromptMode("cap");
        composer.appendString("hi");
        composer.setPromptCursor(99);
        assertEquals(2, composer.promptCursor());
        composer.setPromptCursor(-5);
        assertEquals(0, composer.promptCursor());
    }

    @Test
    public void setPromptCursor_in_NAME_mode_is_noop() {
        composer.startName();
        composer.setPromptCursor(5);
        // No prompt-changed callback fired in NAME mode.
        assertEquals(0, ui.promptChangedCount);
    }

    @Test
    public void cancel_clears_state_and_fires_end() {
        composer.startName();
        composer.enterPromptMode("cap");
        composer.appendString("hello");
        composer.cancel();
        assertFalse(composer.isActive());
        assertNull(composer.mode());
        assertEquals("", composer.query());
        assertTrue(ui.composeEnded);
    }

    @Test
    public void appendString_noop_when_inactive() {
        composer.appendString("hello");
        assertEquals(0, ui.nameChangedCount);
        assertEquals(0, ui.promptChangedCount);
    }
}
