package io.mosip.registration.livenessmanager.engine;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import io.mosip.liveness.core.api.ModelSource;

/**
 * {@link ModelSource} over the signed model bundle in the APK assets ({@value #ROOT}). The Gradle task
 * {@code :livenessmanager:prepareLivenessModels} copies the bundle there after verifying it; the engine's
 * {@code ModelRegistry} verifies the manifest signature and every model's SHA-256 again when it reads them.
 */
public final class AssetModelSource implements ModelSource {

    /** Asset folder of the bundle. */
    public static final String ROOT = "liveness/models";

    private final AssetManager assets;
    private final String root;

    /**
     * Creates a source over the default asset folder.
     *
     * @param assets the application's assets
     */
    public AssetModelSource(AssetManager assets) {
        this(assets, ROOT);
    }

    /**
     * Creates a source over any asset folder.
     *
     * @param assets asset manager
     * @param root   folder inside the assets, without a trailing slash
     */
    public AssetModelSource(AssetManager assets, String root) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public byte[] read(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("..")) {
            throw new FileNotFoundException("invalid bundle path: " + path);
        }
        try (InputStream in = assets.open(root + "/" + path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 8192));
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains(path)) {
                throw new FileNotFoundException(path);
            }
            throw e;
        }
    }
}
