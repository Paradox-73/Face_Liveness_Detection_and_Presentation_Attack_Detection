/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.mesh;

import io.mosip.liveness.core.vision.FaceMesh;

import java.util.Objects;

/**
 * Output of {@link FaceMeshLandmarker}: the mesh in image pixels and the model's face-presence probability.
 */
public final class MeshLandmarks {

    private final FaceMesh mesh;
    private final double presence;

    /**
     * Creates the result.
     *
     * @param mesh     478 points in image pixels
     * @param presence probability 0-1 that the crop holds a face
     */
    public MeshLandmarks(FaceMesh mesh, double presence) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.presence = presence;
    }

    /**
     * Returns the mesh.
     *
     * @return 478 points in image pixels
     */
    public FaceMesh mesh() {
        return mesh;
    }

    /**
     * Returns the face-presence probability; MediaPipe treats values below 0.5 as a lost face.
     *
     * @return probability 0-1
     */
    public double presence() {
        return presence;
    }

    @Override
    public String toString() {
        return "MeshLandmarks[" + mesh + ", presence=" + presence + "]";
    }
}
