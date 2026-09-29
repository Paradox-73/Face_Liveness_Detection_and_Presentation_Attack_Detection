# ONNX Runtime calls back into its Java classes from native code.
-keep class ai.onnxruntime.** { *; }
# The engine is found through java.util.ServiceLoader.
-keep class * implements io.mosip.liveness.core.api.LivenessEngineProvider { *; }
