package com.prince.turtlekeyboard.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

// Robolectric's default API level is 18; deleteSurroundingTextInCodePoints (API 24+)
// isn't on the stub BaseInputConnection so the call throws NoSuchMethodError. Pin
// to our minSdk so the method is present at runtime.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 24)
public class InputCommitterTest {

    /** Recording IC that captures call sequence + lets tests seed text-around-cursor. */
    private static final class RecordingIc extends BaseInputConnection {
        final List<String> calls = new ArrayList<>();
        CharSequence textBefore = "";
        CharSequence textAfter = "";
        CharSequence selected = null;

        RecordingIc(View v) { super(v, true); }

        @Override public CharSequence getTextBeforeCursor(int n, int f) {
            calls.add("before:" + n);
            return textBefore;
        }
        @Override public CharSequence getTextAfterCursor(int n, int f) {
            calls.add("after:" + n);
            return textAfter;
        }
        @Override public CharSequence getSelectedText(int f) {
            calls.add("selected");
            return selected;
        }
        @Override public boolean commitText(CharSequence t, int p) {
            calls.add("commit:" + t);
            return true;
        }
        @Override public boolean deleteSurroundingText(int b, int a) {
            calls.add("delete:" + b + "," + a);
            return true;
        }
        @Override public boolean deleteSurroundingTextInCodePoints(int b, int a) {
            // backspace() now prefers the codepoint variant (API 24+) so emoji-aware
            // hosts delete a whole grapheme; mirror the same call format.
            calls.add("delete:" + b + "," + a);
            return true;
        }
        @Override public boolean beginBatchEdit() {
            calls.add("begin");
            return true;
        }
        @Override public boolean endBatchEdit() {
            calls.add("end");
            return true;
        }
    }

    private View view;
    private RecordingIc ic;
    private InputCommitter committer;

    @Before
    public void setUp() {
        view = new TextView(ApplicationProvider.getApplicationContext());
        ic = new RecordingIc(view);
        committer = new InputCommitter(() -> ic);
    }

    @Test
    public void null_connection_is_safe() {
        InputCommitter c = new InputCommitter(() -> null);
        // Every method should no-op rather than NPE.
        c.commitChar('a');
        c.commitText("hi");
        c.backspace();
        c.deleteBeforeCursor(5);
        c.replaceAll("x");
        assertEquals("", c.textBeforeCursor(10).toString());
        assertEquals("", c.textAfterCursor(10).toString());
        assertEquals("", c.getAllText().toString());
    }

    @Test
    public void commitText_writes_to_ic() {
        committer.commitText("hi");
        assertTrue(ic.calls.contains("commit:hi"));
    }

    @Test
    public void commitChar_writes_single_character() {
        committer.commitChar('a');
        assertTrue(ic.calls.contains("commit:a"));
    }

    @Test
    public void backspace_with_no_selection_deletes_one_char_before() {
        ic.selected = null;
        committer.backspace();
        assertTrue(ic.calls.contains("delete:1,0"));
    }

    @Test
    public void backspace_with_selection_replaces_selection_with_empty() {
        ic.selected = "abc";
        committer.backspace();
        // Selection present: commit empty string to delete it.
        assertTrue(ic.calls.contains("commit:"));
    }

    @Test
    public void getAllText_concatenates_before_selection_after() {
        ic.textBefore = "hello ";
        ic.selected = "WORLD";
        ic.textAfter = " bye";
        assertEquals("hello WORLD bye", committer.getAllText().toString());
    }

    @Test
    public void getAllText_handles_null_selection() {
        ic.textBefore = "hi";
        ic.selected = null;
        ic.textAfter = "!";
        assertEquals("hi!", committer.getAllText().toString());
    }

    @Test
    public void replaceAll_batches_clear_then_delete_then_commit() {
        ic.selected = "old";
        committer.replaceAll("new");
        int begin = ic.calls.indexOf("begin");
        int end = ic.calls.indexOf("end");
        int commitNew = ic.calls.indexOf("commit:new");
        assertTrue("missing begin", begin >= 0);
        assertTrue("missing end", end > begin);
        assertTrue("missing commit:new", commitNew > begin && commitNew < end);
        // The clear-selection commit ("commit:") also lives inside the batch.
        assertTrue(ic.calls.contains("commit:"));
        // Delete-surrounding clears any stray text around the cursor.
        assertTrue(ic.calls.contains("delete:4000,4000"));
    }
}
