import 'package:flutter/material.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/ui/liveness/liveness_texts.dart';

/// Severity of a liveness message; every level has its own icon, colour and
/// text, so the state never depends on colour alone.
enum LivenessSeverity { success, info, error }

/// One-line liveness result (face captured, face verified, failure with
/// guidance, attempts used up). Announced to screen readers when it changes.
class LivenessOutcomeBanner extends StatelessWidget {
  const LivenessOutcomeBanner({
    super.key,
    required this.text,
    required this.severity,
  });

  /// Banner for a resident or onboarding capture outcome.
  factory LivenessOutcomeBanner.capture(
      String text, LivenessOutcome outcome) {
    return LivenessOutcomeBanner(
      key: const ValueKey('liveness-capture-outcome'),
      text: text,
      severity: severityOf(outcome),
    );
  }

  final String text;
  final LivenessSeverity severity;

  // Text colours with at least 4.5:1 contrast on their background.
  static const Color _successColor = Color(0xFF1E6B34);
  static const Color _infoColor = Color(0xFF1C43A1);
  static const Color _errorColor = Color(0xFFA4262C);

  /// Severity of an outcome: passed is success, cancelled is info, the rest
  /// (failed, blocked) is error.
  static LivenessSeverity severityOf(LivenessOutcome outcome) {
    switch (outcome.status) {
      case LivenessStatus.passed:
        return LivenessSeverity.success;
      case LivenessStatus.cancelled:
      case LivenessStatus.notRequired:
        return LivenessSeverity.info;
      default:
        return LivenessSeverity.error;
    }
  }

  Color get _color {
    switch (severity) {
      case LivenessSeverity.success:
        return _successColor;
      case LivenessSeverity.info:
        return _infoColor;
      case LivenessSeverity.error:
        return _errorColor;
    }
  }

  IconData get _icon {
    switch (severity) {
      case LivenessSeverity.success:
        return Icons.verified_user_outlined;
      case LivenessSeverity.info:
        return Icons.info_outline;
      case LivenessSeverity.error:
        return Icons.error_outline;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _color;
    return Semantics(
      liveRegion: true,
      container: true,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsetsDirectional.fromSTEB(12, 10, 12, 10),
        decoration: BoxDecoration(
          color: color.withOpacity(0.08),
          border: Border.all(color: color, width: 1),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(_icon, color: color, size: 24, semanticLabel: null),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                text,
                style: TextStyle(
                  color: color,
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
