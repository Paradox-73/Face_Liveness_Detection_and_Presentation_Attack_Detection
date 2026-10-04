/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';

/// [LivenessService] over the native `LivenessApi`. A failed platform call
/// never throws into the screens: it reads as "liveness not available" or as
/// a failed authentication, so no face is accepted by mistake.
class LivenessServiceImpl implements LivenessService {
  LivenessServiceImpl({LivenessApi? api}) : _api = api ?? LivenessApi();

  final LivenessApi _api;

  static LivenessOutcome _failed(String errorCode) => LivenessOutcome(
      status: 'FAILED', errorCode: errorCode, attemptsUsed: 0, maxAttempts: 0);

  static ModelBundleOutcome _bundleStatus(String status) => ModelBundleOutcome(
      status: status,
      bundle: '',
      version: '',
      previousVersion: '',
      fromStore: false);

  @override
  Future<bool> isEnforced(String workflow) async {
    try {
      return await _api.isEnforced(workflow);
    } on PlatformException catch (e) {
      debugPrint('LivenessApi.isEnforced failed: ${e.code}');
      return false;
    }
  }

  @override
  Future<bool> isFaceAuthAvailable(String userId, String workflow) async {
    try {
      return await _api.isFaceAuthAvailable(userId, workflow);
    } on PlatformException catch (e) {
      debugPrint('LivenessApi.isFaceAuthAvailable failed: ${e.code}');
      return false;
    }
  }

  @override
  Future<void> setLanguage(String languageCode) async {
    try {
      await _api.setLanguage(languageCode);
    } on PlatformException catch (e) {
      debugPrint('LivenessApi.setLanguage failed: ${e.code}');
    }
  }

  @override
  Future<LivenessOutcome> getLastCaptureOutcome(String fieldId) async {
    try {
      return await _api.getLastCaptureOutcome(fieldId);
    } on PlatformException catch (e) {
      debugPrint('LivenessApi.getLastCaptureOutcome failed: ${e.code}');
      return LivenessOutcome(
          status: 'NOT_REQUIRED', attemptsUsed: 0, maxAttempts: 0);
    }
  }

  @override
  Future<LivenessOutcome> authenticateFace(
      String userId, String workflow) async {
    try {
      return await _api.authenticateFace(userId, workflow);
    } on PlatformException catch (e) {
      debugPrint('LivenessApi.authenticateFace failed: ${e.code}');
      return _failed('ENGINE_UNAVAILABLE');
    }
  }

  @override
  Future<ModelBundleOutcome> importModelBundle(String path) async {
    try {
      return await _api.importModelBundle(path);
    } on PlatformException catch (e) {
      debugPrint('LivenessApi.importModelBundle failed: ${e.code}');
      return _bundleStatus('LOAD_FAILED');
    }
  }

  @override
  Future<ModelBundleOutcome> getModelBundle() async {
    try {
      return await _api.getModelBundle();
    } on PlatformException catch (e) {
      debugPrint('LivenessApi.getModelBundle failed: ${e.code}');
      return _bundleStatus('NOT_FOUND');
    }
  }
}

LivenessService getLivenessServiceImpl() => LivenessServiceImpl();
