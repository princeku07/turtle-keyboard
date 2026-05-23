package com.prince.turtlekeyboard.ai;

import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import com.prince.kbd.core.IntegrationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process pipeline for image data flowing from {@code ImagePickerActivity}
 * to the IME and SPI integrations. Held by {@code TurtleApp} so both the
 * activity and the IME (different Android lifecycles, same process) share a
 * single instance — no static mutable state and no implicit cross-IME coupling.
 *
 * <p>Replaces the static {@code stagedEditImage} / {@code stagedUsImages} /
 * listener AtomicReferences that previously lived on {@link TurtleAiClient}.</p>
 */
public final class StagingPipeline {

    /** Fires when the {@code /edit} slot changes; bytes==null means cleared. */
    public interface EditImageListener {
        void onStaged(@Nullable byte[] bytes, @Nullable String mime);
    }

    /** Fires when the {@code /us} slot changes; bytes==null means cleared. */
    public interface UsImagesListener {
        void onStaged(@Nullable List<byte[]> bytes, @Nullable List<String> mimes);
    }

    private final AtomicReference<ClipImage> editImage = new AtomicReference<>();
    private final AtomicReference<List<ReferenceImage>> usImages = new AtomicReference<>();

    @Nullable private volatile EditImageListener editListener;
    @Nullable private volatile UsImagesListener usListener;

    /** Stages a single image for the next {@code /edit} or {@code /style} dispatch. */
    public void stageEditImage(@Nullable byte[] bytes, @Nullable String mime) {
        if (bytes == null) {
            editImage.set(null);
        } else {
            int[] dims = decodeBounds(bytes);
            editImage.set(new ClipImage(
                    bytes, mime != null ? mime : "image/png", dims[0], dims[1]));
        }
        EditImageListener l = editListener;
        if (l != null) l.onStaged(bytes, mime);
    }

    /** Stages reference photos for {@code /us}; null/empty clears. Lists must be same size when set. */
    public void stageUsImages(@Nullable List<byte[]> bytes, @Nullable List<String> mimes) {
        if (bytes == null || bytes.isEmpty()) {
            usImages.set(null);
        } else {
            List<ReferenceImage> refs = new ArrayList<>(bytes.size());
            for (int i = 0; i < bytes.size(); i++) {
                String m = (mimes != null && i < mimes.size() && mimes.get(i) != null)
                        ? mimes.get(i) : "image/jpeg";
                refs.add(new ReferenceImage(bytes.get(i), m));
            }
            usImages.set(refs);
        }
        UsImagesListener l = usListener;
        if (l != null) l.onStaged(bytes, mimes);
    }

    /** Read-and-clear. Package-visible for {@link TurtleAiClient}. */
    @Nullable
    ClipImage consumeEditImage() { return editImage.getAndSet(null); }

    /** Read-and-clear. Package-visible for {@link TurtleAiClient}. */
    @Nullable
    List<ReferenceImage> consumeUsImages() { return usImages.getAndSet(null); }

    /** SPI-typed read-and-clear for integrations (Sticker, Gif, …). */
    @Nullable
    public IntegrationContext.PickedImage consumeEditImageAsPicked() {
        ClipImage src = editImage.getAndSet(null);
        if (src == null) return null;
        return new IntegrationContext.PickedImage(src.bytes, src.mime);
    }

    public void setEditListener(@Nullable EditImageListener l) { this.editListener = l; }
    public void setUsListener(@Nullable UsImagesListener l) { this.usListener = l; }

    private static int[] decodeBounds(byte[] bytes) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        return new int[]{opts.outWidth, opts.outHeight};
    }
}
