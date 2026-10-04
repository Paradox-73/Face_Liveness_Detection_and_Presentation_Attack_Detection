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

/// Which attempt count the face capture block shows.
enum FaceAttemptSource {
  /// ARC's own capture chips (liveness is not checked for this capture).
  arc,

  /// The liveness count of the capture screen ("attempt n of m").
  liveness,

  /// No count: liveness owns the count but no liveness attempt ran yet, or
  /// it is not known yet whether liveness is checked.
  hidden,
}

/// Decides which count the face block shows. ARC's chips count stored
/// captures, while the liveness capture screen counts liveness attempts
/// (failed ones too), so the two disagree as soon as a liveness attempt
/// fails. While liveness is checked, the liveness screen owns the count: the
/// block shows the liveness count of the last outcome, or nothing before the
/// first attempt.
FaceAttemptSource faceAttemptSource({
  required bool isFace,
  required bool? livenessEnforced,
  LivenessOutcome? outcome,
}) {
  if (!isFace) {
    return FaceAttemptSource.arc;
  }
  if (livenessEnforced == null) {
    return FaceAttemptSource.hidden;
  }
  if (!livenessEnforced) {
    return FaceAttemptSource.arc;
  }
  if (outcome != null &&
      outcome.status != LivenessStatus.notRequired &&
      outcome.maxAttempts > 0) {
    return FaceAttemptSource.liveness;
  }
  return FaceAttemptSource.hidden;
}

/// Builds the liveness count in a screen's own layout.
typedef LivenessCountBuilder = Widget Function(
    BuildContext context, LivenessAttemptChips chips);

/// The attempt count of a biometric capture block: ARC's chips ([arc]) for
/// every modality, replaced for the face by the liveness count while the
/// liveness capture screen owns the count (see [faceAttemptSource]).
class FaceAttemptCount extends StatefulWidget {
  const FaceAttemptCount({
    super.key,
    required this.isFace,
    required this.arc,
    this.outcome,
    this.liveness,
    this.workflow = LivenessWorkflow.registration,
    this.service,
  });

  /// Whether the block captures the face.
  final bool isFace;

  /// ARC's own attempt chips.
  final Widget arc;

  /// Liveness outcome of the last face capture, if one ran.
  final LivenessOutcome? outcome;

  /// Places the liveness chips in the screen's layout; by default the chips
  /// are shown alone.
  final LivenessCountBuilder? liveness;

  /// Liveness workflow of the capture.
  final String workflow;

  /// Liveness service; the platform service when null.
  final LivenessService? service;

  @override
  State<FaceAttemptCount> createState() => _FaceAttemptCountState();
}

class _FaceAttemptCountState extends State<FaceAttemptCount> {
  bool? _enforced;

  @override
  void initState() {
    super.initState();
    if (widget.isFace) {
      _load();
    }
  }

  Future<void> _load() async {
    final service = widget.service ?? LivenessService();
    final enforced = await service.isEnforced(widget.workflow);
    if (mounted) {
      setState(() => _enforced = enforced);
    }
  }

  @override
  Widget build(BuildContext context) {
    final outcome = widget.outcome;
    switch (faceAttemptSource(
        isFace: widget.isFace, livenessEnforced: _enforced, outcome: outcome)) {
      case FaceAttemptSource.arc:
        return widget.arc;
      case FaceAttemptSource.hidden:
        return const SizedBox.shrink(key: ValueKey('face-attempts-hidden'));
      case FaceAttemptSource.liveness:
        final chips = LivenessAttemptChips(
          used: outcome!.attemptsUsed,
          max: outcome.maxAttempts,
          passed: outcome.status == LivenessStatus.passed,
        );
        return widget.liveness?.call(context, chips) ?? chips;
    }
  }
}

/// The liveness count as chips 1..[max]: used attempts filled (the passing
/// one in the success colour, failed ones in the failure colour of the shared
/// status palette), the rest outlined. Display only: unlike ARC's chips they
/// do not select a stored capture.
class LivenessAttemptChips extends StatelessWidget {
  const LivenessAttemptChips({
    super.key,
    required this.used,
    required this.max,
    this.passed = false,
    this.fontSize = 16,
  });

  /// Liveness attempts used, 0 to [max].
  final int used;

  /// Liveness attempts allowed.
  final int max;

  /// Whether the last used attempt passed.
  final bool passed;

  /// Number size.
  final double fontSize;

  /// Colour of chip [index] (1-based), from the shared status palette.
  Color fillOf(int index) {
    if (index > used) {
      return Colors.white;
    }
    return passed && index == used
        ? LivenessPalette.successAccent
        : LivenessPalette.failureAccent;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final shown = used.clamp(0, max);
    return Semantics(
      container: true,
      label: '${l10n?.attempts ?? 'Attempts'}: $shown / $max',
      child: ExcludeSemantics(
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          alignment: WrapAlignment.center,
          children: [
            for (int i = 1; i <= max; i++)
              Container(
                key: ValueKey('liveness-attempt-$i'),
                padding:
                    const EdgeInsets.symmetric(vertical: 4, horizontal: 14),
                decoration: BoxDecoration(
                  color: fillOf(i),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(
                      color: i > shown
                          ? LivenessPalette.neutralBorder
                          : fillOf(i)),
                ),
                child: Text(
                  '$i',
                  style: TextStyle(
                    fontSize: fontSize,
                    fontWeight: FontWeight.w600,
                    color:
                        i > shown ? LivenessPalette.guidanceText : Colors.white,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
