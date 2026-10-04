/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'dart:async';

import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';

/// In-memory [LivenessService] for widget tests: no platform channel.
class FakeLivenessService implements LivenessService {
  FakeLivenessService({
    this.enforced = true,
    this.available = true,
    LivenessOutcome? next,
  }) : next = next ??
            LivenessOutcome(status: 'PASSED', attemptsUsed: 1, maxAttempts: 3);

  bool enforced;
  bool available;
  LivenessOutcome next;
  LivenessOutcome lastCapture =
      LivenessOutcome(status: 'NOT_REQUIRED', attemptsUsed: 0, maxAttempts: 0);

  final List<String> languages = [];
  final List<String> authCalls = [];

  /// When set, [authenticateFace] waits for it (to test the busy state).
  Completer<void>? gate;

  @override
  Future<bool> isEnforced(String workflow) async => enforced;

  @override
  Future<bool> isFaceAuthAvailable(String userId, String workflow) async =>
      available;

  @override
  Future<void> setLanguage(String languageCode) async {
    languages.add(languageCode);
  }

  @override
  Future<LivenessOutcome> getLastCaptureOutcome(String fieldId) async =>
      lastCapture;

  @override
  Future<LivenessOutcome> authenticateFace(
      String userId, String workflow) async {
    authCalls.add('$userId/$workflow');
    if (gate != null) {
      await gate!.future;
    }
    return next;
  }

  /// Answer of [importModelBundle] and [getModelBundle].
  ModelBundleOutcome bundle = ModelBundleOutcome(
      status: 'IMPORTED',
      bundle: 'mosip-liveness-models',
      version: '1.3.0',
      previousVersion: '',
      fromStore: false);

  final List<String> importCalls = [];

  @override
  Future<ModelBundleOutcome> importModelBundle(String path) async {
    importCalls.add(path);
    return bundle;
  }

  @override
  Future<ModelBundleOutcome> getModelBundle() async => bundle;
}
