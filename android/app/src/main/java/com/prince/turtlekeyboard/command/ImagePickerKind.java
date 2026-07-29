package com.prince.turtlekeyboard.command;

/**
 * What kind of image picker (if any) the IME should pre-launch when a command
 * enters prompt mode. Each command declares its kind via
 * {@link CommandRegistry#setImagePicker}; unset commands stay {@link #NONE}.
 */
public enum ImagePickerKind {
    /** No picker. Default. */
    NONE,
    /** Single image into the {@code /edit} staging slot — /edit, /style, /gif, /sticker, … */
    EDIT,
    /** Two images into the {@code /us} staging slot. */
    US
}
