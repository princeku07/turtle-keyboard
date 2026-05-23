package com.prince.turtlekeyboard.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.prince.kbd.core.CommandSpec;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

public class CommandRegistryTest {

    private CommandRegistry registry;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
    }

    @Test
    public void register_and_get_roundtrip() {
        registry.register(new CommandSpec("cap", "Image", "🎨", true, null));
        assertTrue(registry.has("cap"));
        assertEquals("cap", registry.get("cap").name);
        assertEquals("Image", registry.get("cap").label);
    }

    @Test
    public void lookup_is_case_insensitive() {
        registry.register(new CommandSpec("cap", "Image", "🎨", true, null));
        assertTrue(registry.has("CAP"));
        assertNotNull(registry.get("Cap"));
    }

    @Test
    public void get_unknown_returns_null() {
        assertNull(registry.get("nope"));
        assertFalse(registry.has("nope"));
    }

    @Test
    public void get_null_is_safe() {
        assertNull(registry.get(null));
        assertFalse(registry.has(null));
    }

    @Test
    public void all_preserves_registration_order() {
        registry.register(new CommandSpec("a", "A", "", true, null));
        registry.register(new CommandSpec("b", "B", "", true, null));
        registry.register(new CommandSpec("c", "C", "", true, null));
        assertEquals("a", registry.all().get(0).name);
        assertEquals("b", registry.all().get(1).name);
        assertEquals("c", registry.all().get(2).name);
    }

    @Test
    public void last_writer_wins_on_name_collision() {
        registry.register(new CommandSpec("cap", "Old", "", true, null));
        registry.register(new CommandSpec("cap", "New", "", true, null));
        assertEquals("New", registry.get("cap").label);
    }

    @Test
    public void suggestion_source_register_and_lookup() {
        registry.setSuggestionSource("us", PromptSuggestionSource.NONE);
        assertSame(PromptSuggestionSource.NONE, registry.suggestionSourceFor("us"));
        assertSame(PromptSuggestionSource.NONE, registry.suggestionSourceFor("US"));
        assertNull(registry.suggestionSourceFor("other"));
    }

    @Test
    public void suggestion_source_null_removes() {
        registry.setSuggestionSource("us", PromptSuggestionSource.NONE);
        registry.setSuggestionSource("us", null);
        assertNull(registry.suggestionSourceFor("us"));
    }

    @Test
    public void image_picker_defaults_to_none_for_unset_command() {
        assertEquals(ImagePickerKind.NONE, registry.imagePickerFor("unregistered"));
        assertEquals(ImagePickerKind.NONE, registry.imagePickerFor(null));
    }

    @Test
    public void image_picker_register_and_lookup() {
        registry.setImagePicker("edit", ImagePickerKind.EDIT);
        registry.setImagePicker("us", ImagePickerKind.US);
        assertEquals(ImagePickerKind.EDIT, registry.imagePickerFor("edit"));
        assertEquals(ImagePickerKind.US, registry.imagePickerFor("us"));
    }

    @Test
    public void image_picker_NONE_is_treated_as_unset() {
        registry.setImagePicker("edit", ImagePickerKind.EDIT);
        registry.setImagePicker("edit", ImagePickerKind.NONE);
        assertEquals(ImagePickerKind.NONE, registry.imagePickerFor("edit"));
    }

    @Test
    public void prompt_decorator_register_and_lookup() {
        PromptDecorator dec = new PromptDecorator() {};
        registry.setPromptDecorator("style", dec);
        assertSame(dec, registry.promptDecoratorFor("style"));
        assertNull(registry.promptDecoratorFor("missing"));
    }

    @Test
    public void prompt_decorator_null_removes() {
        registry.setPromptDecorator("style", new PromptDecorator() {});
        registry.setPromptDecorator("style", null);
        assertNull(registry.promptDecoratorFor("style"));
    }

    @Test
    public void allSortedFor_null_pkg_returns_registration_order() {
        registry.register(new CommandSpec("a", "A", "", true, null));
        registry.register(new CommandSpec("b", "B", "", true,
                null, Collections.singleton("com.whatsapp")));
        assertEquals("a", registry.allSortedFor(null).get(0).name);
    }

    @Test
    public void allSortedFor_pkg_lifts_affinity_above_rest() {
        registry.register(new CommandSpec("ask",  "Ask",  "", true, null));
        registry.register(new CommandSpec("cap",  "Cap",  "", true,
                null, Collections.singleton("com.whatsapp")));
        // affinity wins over registration order for the matching pkg.
        assertEquals("cap", registry.allSortedFor("com.whatsapp").get(0).name);
    }
}
