/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.runtime;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One ONNX model with a single float input, loaded into an ONNX Runtime session from verified bytes.
 *
 * <p>The input buffer is a reused direct buffer, so {@link #run(float[], long[])} is synchronised: calls from
 * several threads are serialised. {@link #close()} releases the native session.
 */
public final class OrtModel implements AutoCloseable {

    private static boolean telemetryDisabled;

    private final String id;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final List<String> outputNames;
    private FloatBuffer inputBuffer;

    private OrtModel(String id, OrtEnvironment environment, OrtSession session, String inputName,
                     List<String> outputNames) {
        this.id = id;
        this.environment = environment;
        this.session = session;
        this.inputName = inputName;
        this.outputNames = outputNames;
    }

    /**
     * Creates a session from model bytes that the caller has already verified.
     *
     * @param id         model identifier, used in error messages
     * @param modelBytes the ONNX file content
     * @param options    runtime settings
     * @return the loaded model
     * @throws EngineException with {@link EngineErrorCode#MODEL_LOAD} if ONNX Runtime is missing or cannot load the
     *                         model, or if the model does not have exactly one input
     */
    public static OrtModel create(String id, byte[] modelBytes, OnnxRuntimeOptions options) throws EngineException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modelBytes, "modelBytes");
        Objects.requireNonNull(options, "options");
        OrtEnvironment env = environment();
        OrtSession session;
        try (OrtSession.SessionOptions sessionOptions = options.toSessionOptions()) {
            session = env.createSession(modelBytes, sessionOptions);
        } catch (OrtException | RuntimeException e) {
            throw new EngineException(EngineErrorCode.MODEL_LOAD, "ONNX Runtime could not load model " + id, e);
        }
        if (session.getInputNames().size() != 1) {
            closeQuietly(session);
            throw new EngineException(EngineErrorCode.MODEL_LOAD, "model " + id + " must have exactly one input");
        }
        String input = session.getInputNames().iterator().next();
        List<String> outputs = Collections.unmodifiableList(new ArrayList<>(session.getOutputNames()));
        return new OrtModel(id, env, session, input, outputs);
    }

    /**
     * Returns the ONNX Runtime version in use (for diagnostics).
     *
     * @return the version string, for example {@code "1.20.0"}
     * @throws EngineException if ONNX Runtime cannot be loaded
     */
    public static String runtimeVersion() throws EngineException {
        return environment().getVersion();
    }

    private static synchronized OrtEnvironment environment() throws EngineException {
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            if (!telemetryDisabled) {
                env.setTelemetry(false);
                telemetryDisabled = true;
            }
            return env;
        } catch (OrtException | RuntimeException | LinkageError e) {
            throw new EngineException(EngineErrorCode.MODEL_LOAD, "ONNX Runtime is not available on this platform", e);
        }
    }

    /**
     * Returns the model identifier.
     *
     * @return the identifier given at creation
     */
    public String id() {
        return id;
    }

    /**
     * Returns the name of the model input.
     *
     * @return the input name
     */
    public String inputName() {
        return inputName;
    }

    /**
     * Returns the output names in model order.
     *
     * @return an unmodifiable list
     */
    public List<String> outputNames() {
        return outputNames;
    }

    /**
     * Runs the model on one float tensor.
     *
     * @param input values in row-major order; exactly the product of {@code shape}
     * @param shape tensor shape, for example {@code {1, 3, 80, 80}}
     * @return every output by name, in model order, as float arrays
     * @throws EngineException with {@link EngineErrorCode#INFERENCE} if the run fails or an output is not float
     */
    public synchronized Map<String, float[]> run(float[] input, long[] shape) throws EngineException {
        long count = 1;
        for (long dim : shape) {
            count *= dim;
        }
        if (count != input.length) {
            throw new IllegalArgumentException("input has " + input.length + " values but shape needs " + count);
        }
        FloatBuffer buffer = inputBuffer(input.length);
        buffer.put(input, 0, input.length);
        // Through Buffer: the covariant FloatBuffer.flip() of Java 9+ is missing on older Android runtimes.
        ((Buffer) buffer).flip();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer, shape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            Map<String, float[]> out = new LinkedHashMap<>();
            for (int i = 0; i < outputNames.size(); i++) {
                out.put(outputNames.get(i), toFloats(result.get(i)));
            }
            return out;
        } catch (OrtException | RuntimeException e) {
            throw new EngineException(EngineErrorCode.INFERENCE, "inference failed for model " + id, e);
        }
    }

    private FloatBuffer inputBuffer(int length) {
        if (inputBuffer == null || inputBuffer.capacity() < length) {
            inputBuffer = ByteBuffer.allocateDirect(length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        ((Buffer) inputBuffer).clear();
        ((Buffer) inputBuffer).limit(length);
        return inputBuffer;
    }

    private float[] toFloats(OnnxValue value) throws EngineException {
        if (!(value instanceof OnnxTensor)) {
            throw new EngineException(EngineErrorCode.INFERENCE, "model " + id + " returned a non-tensor output");
        }
        FloatBuffer fb = ((OnnxTensor) value).getFloatBuffer();
        if (fb == null) {
            throw new EngineException(EngineErrorCode.INFERENCE, "model " + id + " returned a non-float output");
        }
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    /** Releases the native session. */
    @Override
    public synchronized void close() {
        closeQuietly(session);
    }

    private static void closeQuietly(OrtSession session) {
        try {
            session.close();
        } catch (OrtException | RuntimeException ignored) {
            // Closing is best effort; nothing useful can be done if the runtime refuses.
        }
    }

    @Override
    public String toString() {
        return "OrtModel[" + id + ", input=" + inputName + ", outputs=" + outputNames + "]";
    }
}
