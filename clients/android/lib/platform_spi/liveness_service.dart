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
