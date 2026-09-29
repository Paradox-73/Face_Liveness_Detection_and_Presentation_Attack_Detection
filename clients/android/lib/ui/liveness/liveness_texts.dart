import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';

/// Status values of [LivenessOutcome.status].
class LivenessStatus {
  static const String passed = 'PASSED';
  static const String failed = 'FAILED';
  static const String blocked = 'BLOCKED';
  static const String cancelled = 'CANCELLED';
  static const String notRequired = 'NOT_REQUIRED';
}

/// Text for a face authentication outcome. The native side sends the liveness
/// engine's own message when it has one (same text as the capture screen and
/// the desktop client); otherwise the text comes from the ARB files.
String faceAuthText(AppLocalizations l10n, LivenessOutcome outcome) {
  if (outcome.status == LivenessStatus.passed) {
    return l10n.liveness_face_verified;
  }
  final message = outcome.message;
  if (message != null && message.isNotEmpty) {
    return message;
  }
  return l10n.liveness_auth_errors(outcome.errorCode ?? 'other');
}

/// Text for the liveness outcome of a resident or onboarding face capture.
String captureOutcomeText(AppLocalizations l10n, LivenessOutcome outcome) {
  final message = outcome.message;
  if (message != null && message.isNotEmpty) {
    return message;
  }
  if (outcome.status == LivenessStatus.passed) {
    return l10n.liveness_face_captured;
  }
  return l10n.liveness_capture_status(outcome.status);
}

/// Whether a capture outcome means the face was not stored.
bool isCaptureRefused(LivenessOutcome outcome) =>
    outcome.status != LivenessStatus.passed &&
    outcome.status != LivenessStatus.notRequired;
