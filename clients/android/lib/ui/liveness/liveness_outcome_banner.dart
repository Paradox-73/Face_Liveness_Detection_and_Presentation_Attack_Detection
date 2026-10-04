/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'package:flutter/material.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/ui/liveness/liveness_texts.dart';

/// Status colours shared with the native capture screen
/// (`livenessmanager` values/colors.xml) and the desktop client
/// (`liveness-panel.css`): neutral (information), success, attention and
/// failure, each with a light banner, an accent for borders and icons, and a
/// text colour. Text contrast on its banner (WCAG 2.1): neutral 12.1:1,
/// success 8.0:1, attention 9.3:1, failure 9.1:1.
class LivenessPalette {
  LivenessPalette._();

  static const Color neutralBanner = Color(0xFFE8F1FA);
  static const Color neutralAccent = Color(0xFF005BAA);
  static const Color neutralText = Color(0xFF0B2E4F);
  static const Color successBanner = Color(0xFFE6F4EA);
  static const Color successAccent = Color(0xFF1E7B34);
  static const Color successText = Color(0xFF14532D);
  static const Color attentionBanner = Color(0xFFFFF4DB);
  static const Color attentionAccent = Color(0xFF8A5A00);
  static const Color attentionText = Color(0xFF5B3B00);
  static const Color failureBanner = Color(0xFFFCE8E6);
  static const Color failureAccent = Color(0xFFB3261E);
  static const Color failureText = Color(0xFF7A1712);

  /// Guidance and secondary text on any banner or on white.
  static const Color guidanceText = Color(0xFF1F2933);

  /// Border of an unused attempt chip (non-text, 3:1 or more on white).
  static const Color neutralBorder = Color(0xFF6B7785);
}

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
  factory LivenessOutcomeBanner.capture(String text, LivenessOutcome outcome) {
    return LivenessOutcomeBanner(
      key: const ValueKey('liveness-capture-outcome'),
      text: text,
      severity: severityOf(outcome),
    );
  }

  final String text;
  final LivenessSeverity severity;

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

  /// Text colour of the banner.
  Color get textColor {
    switch (severity) {
      case LivenessSeverity.success:
        return LivenessPalette.successText;
      case LivenessSeverity.info:
        return LivenessPalette.neutralText;
      case LivenessSeverity.error:
        return LivenessPalette.failureText;
    }
  }

  /// Border and icon colour of the banner.
  Color get accentColor {
    switch (severity) {
      case LivenessSeverity.success:
        return LivenessPalette.successAccent;
      case LivenessSeverity.info:
        return LivenessPalette.neutralAccent;
      case LivenessSeverity.error:
        return LivenessPalette.failureAccent;
    }
  }

  /// Background colour of the banner.
  Color get backgroundColor {
    switch (severity) {
      case LivenessSeverity.success:
        return LivenessPalette.successBanner;
      case LivenessSeverity.info:
        return LivenessPalette.neutralBanner;
      case LivenessSeverity.error:
        return LivenessPalette.failureBanner;
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
    return Semantics(
      liveRegion: true,
      container: true,
      // Light banner with an accent border on the start side, as on the
      // native capture screen and the desktop client.
      child: ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsetsDirectional.fromSTEB(12, 10, 12, 10),
          decoration: BoxDecoration(
            color: backgroundColor,
            border: BorderDirectional(
              start: BorderSide(color: accentColor, width: 6),
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(_icon, color: accentColor, size: 24, semanticLabel: null),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  text,
                  style: TextStyle(
                    color: textColor,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
