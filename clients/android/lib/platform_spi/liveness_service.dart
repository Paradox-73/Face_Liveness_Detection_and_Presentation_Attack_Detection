/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_android/liveness_service_impl.dart';

/// Face liveness and face authentication (native `LivenessApi`).
abstract class LivenessService {
  /// Whether face captures in [workflow] need a liveness pass.
  Future<bool> isEnforced(String workflow);

  /// Whether face authentication can be offered to [userId] in [workflow].
  Future<bool> isFaceAuthAvailable(String userId, String workflow);

  /// Language of the capture screen and of the returned messages.
  Future<void> setLanguage(String languageCode);

  /// Liveness outcome of the last face capture of [fieldId].
  Future<LivenessOutcome> getLastCaptureOutcome(String fieldId);

  /// Liveness, then a match against the face [userId] enrolled at onboarding.
  Future<LivenessOutcome> authenticateFace(String userId, String workflow);

  /// Supervisor action: imports the signed model bundle at [path] (folder or
  /// .zip; empty for the app's import folder) and switches the engine to it.
  Future<ModelBundleOutcome> importModelBundle(String path);

  /// The model bundle the liveness engine runs.
  Future<ModelBundleOutcome> getModelBundle();

  factory LivenessService() => getLivenessServiceImpl();
}

/// Workflow IDs of the liveness policy (same IDs as the desktop client).
class LivenessWorkflow {
  static const String registration = 'registration';
  static const String onboardAuth = 'onboard_auth';
  static const String loginAuth = 'login_auth';
  static const String packetAuth = 'packet_auth';
  static const String exceptionAuth = 'exception_auth';
  static const String eodAuth = 'eod_auth';
}
