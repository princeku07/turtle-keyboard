package com.prince.turtlekeyboard.ai;

/** Reference photo for /us — bytes + mime, read from the local cache. */
final class ReferenceImage {
    final byte[] bytes;
    final String mime;

    ReferenceImage(byte[] bytes, String mime) {
        this.bytes = bytes;
        this.mime = mime;
    }
}
