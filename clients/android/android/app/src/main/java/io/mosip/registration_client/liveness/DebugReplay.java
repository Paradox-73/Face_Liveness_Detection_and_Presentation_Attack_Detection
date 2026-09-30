package io.mosip.registration_client.liveness;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.mosip.registration.livenessmanager.LivenessCaptureRequest;

/**
 * Debug builds: lets automated tests and demos drive ARC's real flows (resident capture, onboarding, face
 * authentication) with a recorded frame pack instead of the live camera.
 *
 * <p>The file {@code <external files>/liveness-replay/}{@value #SOURCE_FILE} holds one line: the pack name and,
 * optionally, the frame rate, for example {@code s3-main 15}. The pack itself is a folder next to it (pushed by
 * {@code scripts/android-liveness-demo.sh --push}) or an APK asset pack. The form {@code follow s3n 15} plays
 * {@code s3n-main} and answers challenge prompts with {@code s3n-blink}, {@code s3n-smile} and so on, like a
 * person in front of the camera. A trailing {@code masked} keeps the preview dark, so screenshots show no face.
 * Delete the file to use the camera again.
 */
final class DebugReplay {

    /** Name of the file that selects the replay pack. */
    static final String SOURCE_FILE = "flow-source.txt";

    private static final String TAG = "Liveness";
    private static final String FOLDER = "liveness-replay";
    private static final int DEFAULT_FPS = 15;

    private DebugReplay() {
    }

    /**
     * Switches the request to the selected replay pack, if one is selected.
     *
     * @param context an app context
     * @param request the request being built
     */
    static void apply(Context context, LivenessCaptureRequest.Builder request) {
        File folder = context.getExternalFilesDir(FOLDER);
        File source = folder == null ? null : new File(folder, SOURCE_FILE);
        if (source == null || !source.isFile()) {
            return;
        }
        try {
            String line = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8).trim();
            Selection selection = parse(line);
            if (selection != null) {
                Log.i(TAG, "Debug build: replaying frame pack " + selection.pack
                        + (selection.follow ? " (following prompts)" : "") + " instead of the camera");
                request.replay(selection.pack, selection.fps).replayFollow(selection.follow)
                        .replayMasked(selection.masked);
            }
        } catch (IOException e) {
            Log.w(TAG, "Replay selection unreadable; using the camera", e);
        }
    }

    /**
     * Parses the selection line: {@code [follow] PACK [FPS] [masked]}.
     *
     * @param line for example {@code s3-main 15} or {@code follow s3n 15 masked}
     * @return the selection, or {@code null} for a blank line
     */
    static Selection parse(String line) {
        String[] parts = line == null ? new String[0] : line.trim().split("\s+");
        boolean follow = parts.length > 1 && "follow".equals(parts[0]);
        int first = follow ? 1 : 0;
        if (parts.length <= first || parts[first].isEmpty()) {
            return null;
        }
        int fps = DEFAULT_FPS;
        boolean masked = false;
        for (int i = first + 1; i < parts.length; i++) {
            if ("masked".equals(parts[i])) {
                masked = true;
            } else if (i == first + 1) {
                fps = parseFps(parts[i]);
            }
        }
        return new Selection(parts[first], fps, follow, masked);
    }

    private static int parseFps(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return DEFAULT_FPS;
        }
    }

    /** A selected pack (or pack prefix), its frame rate, whether it answers prompts and whether it is hidden. */
    static final class Selection {
        final String pack;
        final int fps;
        final boolean follow;
        final boolean masked;

        Selection(String pack, int fps, boolean follow, boolean masked) {
            this.pack = pack;
            this.fps = fps;
            this.follow = follow;
            this.masked = masked;
        }
    }
}
