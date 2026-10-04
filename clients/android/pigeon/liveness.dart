/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'package:pigeon/pigeon.dart';

/// Outcome of a face liveness capture or a face authentication, as the
/// Flutter screens show it. Carries no score, image or template.
class LivenessOutcome {
  /// PASSED, FAILED, BLOCKED, CANCELLED or NOT_REQUIRED.
  final String status;

  /// Face authentication status when not PASSED (for example FACE_MISMATCH,
  /// NOT_SUPERVISOR, FACE_NOT_ENROLLED, MAX_ATTEMPTS); null for captures.
  final String? errorCode;

  /// Text in the display language, from the liveness engine's message
  /// catalogue (same text as the capture screen and the desktop client);
  /// null when the screen picks its own text from [errorCode].
  final String? message;

  final int attemptsUsed;
  final int maxAttempts;

  LivenessOutcome({
    required this.status,
    this.errorCode,
    this.message,
    required this.attemptsUsed,
    required this.maxAttempts,
  });
}

/// Liveness model bundle: the one the engine runs, or the result of an
/// offline model import. Carries no file content.
class ModelBundleOutcome {
  /// IMPORTED, NOT_ALLOWED, BUSY, NOT_SUPPORTED, NOT_FOUND, INVALID, TOO_OLD or
  /// LOAD_FAILED (see ModelBundleImporter.Status).
  final String status;

  /// Bundle name from the signed manifest; empty when it was not verified.
  final String bundle;

  /// Bundle version from the signed manifest; empty when it was not verified.
  final String version;

  /// Version that ran before the import, or the version a refused bundle
  /// had to exceed; empty when unknown.
  final String previousVersion;

  /// Whether the bundle comes from the import store rather than the APK.
  final bool fromStore;

  ModelBundleOutcome({
    required this.status,
    required this.bundle,
    required this.version,
    required this.previousVersion,
    required this.fromStore,
  });
}

@HostApi()
abstract class LivenessApi {
  /// Whether a face capture in [workflow] (registration, onboard_auth,
  /// login_auth, packet_auth, exception_auth, eod_auth) needs a liveness pass.
  bool isEnforced(String workflow);

  /// Whether face authentication can be offered to [userId] in [workflow]:
  /// liveness on, account not locked, supervisor role where needed, face
  /// enrolled at onboarding.
  bool isFaceAuthAvailable(String userId, String workflow);

  /// Language of the capture screen and of the returned messages (ARC code,
  /// for example eng or ara).
  void setLanguage(String languageCode);

  /// Liveness outcome of the last face capture of [fieldId] (resident face
  /// or operatorBiometrics); NOT_REQUIRED when no liveness capture ran.
  LivenessOutcome getLastCaptureOutcome(String fieldId);

  /// Liveness, then a 1:1 match against the face [userId] enrolled at
  /// onboarding. Works offline.
  @async
  LivenessOutcome authenticateFace(String userId, String workflow);

  /// Supervisor action: verifies the signed model bundle at [path] (a folder
  /// or a .zip file the app can read; empty for the app's import folder
  /// Android/data/<app>/files/liveness-models), stores it and switches the
  /// liveness engine to it. Signed-in user must be a supervisor or admin.
  @async
  ModelBundleOutcome importModelBundle(String path);

  /// The model bundle the liveness engine runs (status IMPORTED), or
  /// NOT_FOUND when none can be read.
  ModelBundleOutcome getModelBundle();
}
