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
 * {@code scripts/android-liveness-demo.sh --push}) or an APK asset pack. Delete the file to use the camera again.
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
                Log.i(TAG, "Debug build: replaying frame pack " + selection.pack + " instead of the camera");
                request.replay(selection.pack, selection.fps);
            }
        } catch (IOException e) {
            Log.w(TAG, "Replay selection unreadable; using the camera", e);
        }
    }

    /**
     * Parses the selection line.
     *
     * @param line for example {@code s3-main 15}
     * @return the selection, or {@code null} for a blank line
     */
    static Selection parse(String line) {
        String[] parts = line == null ? new String[0] : line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        int fps = DEFAULT_FPS;
        if (parts.length > 1) {
            try {
                fps = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                fps = DEFAULT_FPS;
            }
        }
        return new Selection(parts[0], fps);
    }

    /** A selected pack and its frame rate. */
    static final class Selection {
        final String pack;
        final int fps;

        Selection(String pack, int fps) {
            this.pack = pack;
            this.fps = fps;
        }
    }
}
