package com.prince.turtlekeyboard.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.prince.kbd.core.CommandSpec;
import com.prince.turtlekeyboard.input.InputCommitter;

import org.junit.Before;
import org.junit.Test;

public class SlashCommandDetectorTest {

    /** Stub committer with a settable text-before-cursor and no real InputConnection. */
    private static final class StubCommitter extends InputCommitter {
        CharSequence before = "";
        StubCommitter() { super(() -> null); }
        @Override public CharSequence textBeforeCursor(int n) { return before; }
    }

    private StubCommitter committer;
    private CommandRegistry registry;
    private SlashCommand fired;
    private SlashCommandDetector detector;

    @Before
    public void setUp() {
        committer = new StubCommitter();
        registry = new CommandRegistry();
        registry.register(new CommandSpec("cap", "Image", "🎨", true, null));
        fired = null;
        detector = new SlashCommandDetector(committer, registry, cmd -> fired = cmd);
    }

    @Test
    public void fires_on_known_command_terminated_by_space() {
        committer.before = "/cap ";
        detector.onTextChanged();
        assertEquals("cap", fired.name);
    }

    @Test
    public void fires_when_command_is_mid_sentence_after_whitespace() {
        committer.before = "hey /cap ";
        detector.onTextChanged();
        assertEquals("cap", fired.name);
    }

    @Test
    public void does_not_fire_on_mid_word_slash() {
        // Slash glued to preceding text (e.g. "http://") is not a command.
        committer.before = "http://cap ";
        detector.onTextChanged();
        assertNull(fired);
    }

    @Test
    public void does_not_fire_for_unregistered_command() {
        committer.before = "/unknown ";
        detector.onTextChanged();
        assertNull(fired);
    }

    @Test
    public void does_not_fire_without_terminator() {
        committer.before = "/cap";
        detector.onTextChanged();
        assertNull(fired);
    }

    @Test
    public void does_not_fire_when_terminator_separated_by_newline() {
        // Newline between '/' and command breaks the token.
        committer.before = "/\ncap ";
        detector.onTextChanged();
        assertNull(fired);
    }

    @Test
    public void empty_buffer_is_safe() {
        committer.before = "";
        detector.onTextChanged();
        assertNull(fired);
    }
}
