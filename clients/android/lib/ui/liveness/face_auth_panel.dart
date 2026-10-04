/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';
import 'package:registration_client/ui/liveness/liveness_outcome_banner.dart';
import 'package:registration_client/ui/liveness/liveness_texts.dart';

/// "Authenticate with face" option for operator sign-in, operator packet
/// authentication and supervisor approval.
///
/// Shows nothing when face authentication is not available for [userId] in
/// [workflow] (liveness off, no enrolled face, not a supervisor for a
/// supervisor workflow), so the screen keeps its password form only. Pressing
/// the button opens the native capture screen (liveness, then a match against
/// the onboarding face, offline) and shows the outcome below the button.
class FaceAuthPanel extends StatefulWidget {
  const FaceAuthPanel({
    super.key,
    required this.userId,
    required this.workflow,
    required this.languageCode,
    required this.onResult,
    this.label,
    this.showWhenUnavailable = false,
    this.service,
  });

  /// User to verify.
  final String userId;

  /// Liveness workflow: login_auth, packet_auth, eod_auth or exception_auth.
  final String workflow;

  /// ARC language code of the capture screen (eng, ara, ...).
  final String languageCode;

  /// Called with every outcome; PASSED means the user is authenticated.
  final ValueChanged<LivenessOutcome> onResult;

  /// Button text; defaults to "Authenticate with face".
  final String? label;

  /// Also show the button when the precheck fails (the press then explains
  /// why, for example "This user is not a supervisor").
  final bool showWhenUnavailable;

  /// Service override for tests.
  final LivenessService? service;

  @override
  State<FaceAuthPanel> createState() => _FaceAuthPanelState();
}

class _FaceAuthPanelState extends State<FaceAuthPanel> {
  LivenessService? _defaultService;
  LivenessService get _service =>
      widget.service ?? (_defaultService ??= LivenessService());
  bool? _available;
  bool _busy = false;
  LivenessOutcome? _outcome;

  @override
  void initState() {
    super.initState();
    _checkAvailability();
  }

  @override
  void didUpdateWidget(covariant FaceAuthPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.userId != widget.userId ||
        oldWidget.workflow != widget.workflow ||
        oldWidget.service != widget.service) {
      _outcome = null;
      _checkAvailability();
    }
  }

  Future<void> _checkAvailability() async {
    final enforced = await _service.isEnforced(widget.workflow);
    final available = widget.userId.trim().isNotEmpty &&
        await _service.isFaceAuthAvailable(widget.userId, widget.workflow);
    if (!mounted) return;
    setState(() {
      _available = available || (widget.showWhenUnavailable && enforced);
    });
  }

  Future<void> _authenticate() async {
    setState(() {
      _busy = true;
      _outcome = null;
    });
    await _service.setLanguage(widget.languageCode);
    final outcome =
        await _service.authenticateFace(widget.userId, widget.workflow);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _outcome = outcome;
    });
    widget.onResult(outcome);
  }

  @override
  Widget build(BuildContext context) {
    if (_available != true) {
      return const SizedBox.shrink();
    }
    final l10n = AppLocalizations.of(context)!;
    final outcome = _outcome;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          height: 52,
          child: OutlinedButton.icon(
            key: const ValueKey('face-auth-button'),
            onPressed: _busy ? null : _authenticate,
            icon: _busy
                ? const SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(strokeWidth: 2.5),
                  )
                : const Icon(Icons.face_outlined, size: 26),
            label: Text(
              widget.label ?? l10n.liveness_authenticate_with_face,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
          ),
        ),
        if (outcome != null) ...[
          const SizedBox(height: 12),
          LivenessOutcomeBanner(
            key: const ValueKey('face-auth-outcome'),
            text: faceAuthText(l10n, outcome),
            severity: LivenessOutcomeBanner.severityOf(outcome),
          ),
        ],
      ],
    );
  }
}
