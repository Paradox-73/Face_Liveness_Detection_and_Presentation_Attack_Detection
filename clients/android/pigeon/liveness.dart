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
}
