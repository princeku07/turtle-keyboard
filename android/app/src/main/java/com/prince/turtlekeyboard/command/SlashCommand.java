package com.prince.turtlekeyboard.command;

/** Parsed slash invocation, e.g. "/cap a samurai cat" → name="cap", prompt="a samurai cat". */
public class SlashCommand {
    public final String name;
    public final String prompt;
    public final String raw;

    public SlashCommand(String name, String prompt, String raw) {
        this.name = name;
        this.prompt = prompt;
        this.raw = raw;
    }

    /** Returns null if the input does not start with a slash followed by a name. */
    public static SlashCommand parse(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.length() < 2 || s.charAt(0) != '/') return null;
        int sp = s.indexOf(' ');
        String name; String prompt;
        if (sp < 0) { name = s.substring(1); prompt = ""; }
        else { name = s.substring(1, sp); prompt = s.substring(sp + 1).trim(); }
        if (name.isEmpty()) return null;
        return new SlashCommand(name.toLowerCase(), prompt, s);
    }
}
