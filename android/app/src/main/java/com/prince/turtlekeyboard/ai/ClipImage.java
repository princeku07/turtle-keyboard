package com.prince.turtlekeyboard.ai;

/** Bytes + mime + decoded dimensions for a staged or clipboard image. */
final class ClipImage {
    final byte[] bytes;
    final String mime;
    final int width;
    final int height;

    ClipImage(byte[] bytes, String mime, int width, int height) {
        this.bytes = bytes;
        this.mime = mime;
        this.width = width;
        this.height = height;
    }

    /** Aspect ratio (w/h), or 0 if dimensions unknown. */
    float aspect() {
        return (width > 0 && height > 0) ? (float) width / height : 0f;
    }
}
