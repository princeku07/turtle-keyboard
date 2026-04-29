package com.prince.turtlekeyboard.ai;

/** Result of an AI command invocation. Image results carry a clipboard URI; text results
 *  carry the replacement string; suggestion results carry up to three options. */
public class AiResult {

    public enum Kind { TEXT, IMAGE, SUGGESTIONS, ERROR }

    public final Kind kind;
    public final String text;
    public final String imageUri;
    public final String[] suggestions;
    public final String error;

    private AiResult(Kind k, String text, String img, String[] sug, String err) {
        this.kind = k; this.text = text; this.imageUri = img; this.suggestions = sug; this.error = err;
    }

    public static AiResult text(String t) { return new AiResult(Kind.TEXT, t, null, null, null); }
    public static AiResult image(String uri) { return new AiResult(Kind.IMAGE, null, uri, null, null); }
    public static AiResult suggestions(String[] s) { return new AiResult(Kind.SUGGESTIONS, null, null, s, null); }
    public static AiResult error(String msg) { return new AiResult(Kind.ERROR, null, null, null, msg); }
}
