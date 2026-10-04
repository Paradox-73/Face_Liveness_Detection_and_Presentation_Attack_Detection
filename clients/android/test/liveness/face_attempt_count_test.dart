/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/ui/liveness/face_attempt_count.dart';
import 'package:registration_client/ui/liveness/liveness_outcome_banner.dart';

import 'fake_liveness_service.dart';

LivenessOutcome _outcome(String status, {int used = 1, int max = 3}) =>
    LivenessOutcome(status: status, attemptsUsed: used, maxAttempts: max);

Widget _app(Widget child) => MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: Center(child: SizedBox(width: 500, child: child))),
    );

const _arcChips = Text('ARC chips', key: ValueKey('arc-chips'));

FaceAttemptCount _count(FakeLivenessService service,
        {bool isFace = true, LivenessOutcome? outcome}) =>
    FaceAttemptCount(
      isFace: isFace,
      outcome: outcome,
      service: service,
      arc: _arcChips,
    );

double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  return (math.max(la, lb) + 0.05) / (math.min(la, lb) + 0.05);
}

void main() {
  final arc = find.byKey(const ValueKey('arc-chips'));
  final liveness = find.byType(LivenessAttemptChips);

  group('faceAttemptSource', () {
    test('other modalities keep ARC chips', () {
      expect(
          faceAttemptSource(
              isFace: false,
              livenessEnforced: true,
              outcome: _outcome('FAILED')),
          FaceAttemptSource.arc);
    });

    test('face without liveness keeps ARC chips', () {
      expect(
          faceAttemptSource(
              isFace: true,
              livenessEnforced: false,
              outcome: _outcome('FAILED')),
          FaceAttemptSource.arc);
    });

    test('face with liveness shows the liveness count once an attempt ran', () {
      expect(
          faceAttemptSource(
              isFace: true,
              livenessEnforced: true,
              outcome: _outcome('FAILED')),
          FaceAttemptSource.liveness);
      expect(
          faceAttemptSource(
              isFace: true,
              livenessEnforced: true,
              outcome: _outcome('BLOCKED', used: 3)),
          FaceAttemptSource.liveness);
    });

    test('face with liveness hides the count before the first attempt', () {
      expect(faceAttemptSource(isFace: true, livenessEnforced: true),
          FaceAttemptSource.hidden);
      expect(
          faceAttemptSource(
              isFace: true,
              livenessEnforced: true,
              outcome: _outcome('NOT_REQUIRED', used: 0, max: 0)),
          FaceAttemptSource.hidden);
      expect(faceAttemptSource(isFace: true, livenessEnforced: null),
          FaceAttemptSource.hidden);
    });
  });

  group('FaceAttemptCount', () {
    testWidgets('fingerprints and iris show ARC chips', (tester) async {
      await tester
          .pumpWidget(_app(_count(FakeLivenessService(), isFace: false)));
      await tester.pumpAndSettle();
      expect(arc, findsOneWidget);
      expect(liveness, findsNothing);
    });

    testWidgets('face with liveness hides ARC chips before the first attempt',
        (tester) async {
      await tester.pumpWidget(_app(_count(FakeLivenessService())));
      await tester.pumpAndSettle();
      expect(arc, findsNothing);
      expect(liveness, findsNothing);
      expect(
          find.byKey(const ValueKey('face-attempts-hidden')), findsOneWidget);
    });

    testWidgets('face with liveness shows the liveness count, not ARC chips',
        (tester) async {
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(_app(
          _count(FakeLivenessService(), outcome: _outcome('FAILED', used: 2))));
      await tester.pumpAndSettle();

      expect(arc, findsNothing);
      expect(liveness, findsOneWidget);
      for (int i = 1; i <= 3; i++) {
        expect(find.byKey(ValueKey('liveness-attempt-$i')), findsOneWidget);
      }
      expect(find.bySemanticsLabel('Attempts: 2 / 3'), findsOneWidget);
      final chips = tester.widget<LivenessAttemptChips>(liveness);
      expect(chips.fillOf(1), LivenessPalette.failureAccent);
      expect(chips.fillOf(2), LivenessPalette.failureAccent);
      expect(chips.fillOf(3), Colors.white);
      handle.dispose();
    });

    testWidgets('a pass shows the passing attempt in the success colour',
        (tester) async {
      await tester.pumpWidget(_app(
          _count(FakeLivenessService(), outcome: _outcome('PASSED', used: 2))));
      await tester.pumpAndSettle();
      final chips = tester.widget<LivenessAttemptChips>(liveness);
      expect(chips.fillOf(1), LivenessPalette.failureAccent);
      expect(chips.fillOf(2), LivenessPalette.successAccent);
    });

    testWidgets('face without liveness keeps ARC chips', (tester) async {
      await tester.pumpWidget(_app(_count(FakeLivenessService(enforced: false),
          outcome: _outcome('FAILED'))));
      await tester.pumpAndSettle();
      expect(arc, findsOneWidget);
      expect(liveness, findsNothing);
    });

    testWidgets('the screen places the liveness chips in its own layout',
        (tester) async {
      await tester.pumpWidget(_app(FaceAttemptCount(
        isFace: true,
        outcome: _outcome('FAILED'),
        service: FakeLivenessService(),
        arc: _arcChips,
        liveness: (context, chips) =>
            Column(children: [const Text('Attempts'), chips]),
      )));
      await tester.pumpAndSettle();
      expect(find.text('Attempts'), findsOneWidget);
      expect(liveness, findsOneWidget);
    });
  });

  group('shared status palette', () {
    test('banner texts keep 4.5:1 contrast on their banner', () {
      final pairs = {
        'neutral': [LivenessPalette.neutralText, LivenessPalette.neutralBanner],
        'success': [LivenessPalette.successText, LivenessPalette.successBanner],
        'attention': [
          LivenessPalette.attentionText,
          LivenessPalette.attentionBanner
        ],
        'failure': [LivenessPalette.failureText, LivenessPalette.failureBanner],
      };
      pairs.forEach((name, pair) {
        expect(_contrast(pair[0], pair[1]), greaterThanOrEqualTo(4.5),
            reason: name);
        expect(_contrast(LivenessPalette.guidanceText, pair[1]),
            greaterThanOrEqualTo(4.5),
            reason: '$name guidance');
      });
      for (final accent in [
        LivenessPalette.neutralAccent,
        LivenessPalette.successAccent,
        LivenessPalette.attentionAccent,
        LivenessPalette.failureAccent,
      ]) {
        expect(_contrast(Colors.white, accent), greaterThanOrEqualTo(4.5));
      }
      expect(_contrast(LivenessPalette.neutralBorder, Colors.white),
          greaterThanOrEqualTo(3.0));
    });

    test('same colours as the native capture screen', () {
      final file =
          File('android/livenessmanager/src/main/res/values/colors.xml');
      final xml = file.readAsStringSync();
      Color native(String name) {
        final m = RegExp('<color name="$name">#([0-9A-Fa-f]{8})</color>')
            .firstMatch(xml);
        expect(m, isNotNull, reason: name);
        return Color(int.parse(m!.group(1)!, radix: 16));
      }

      expect(native('liveness_status_neutral_banner'),
          LivenessPalette.neutralBanner);
      expect(native('liveness_status_neutral_accent'),
          LivenessPalette.neutralAccent);
      expect(
          native('liveness_status_neutral_text'), LivenessPalette.neutralText);
      expect(native('liveness_status_success_banner'),
          LivenessPalette.successBanner);
      expect(native('liveness_status_success_accent'),
          LivenessPalette.successAccent);
      expect(
          native('liveness_status_success_text'), LivenessPalette.successText);
      expect(native('liveness_status_attention_banner'),
          LivenessPalette.attentionBanner);
      expect(native('liveness_status_attention_accent'),
          LivenessPalette.attentionAccent);
      expect(native('liveness_status_attention_text'),
          LivenessPalette.attentionText);
      expect(native('liveness_status_failure_banner'),
          LivenessPalette.failureBanner);
      expect(native('liveness_status_failure_accent'),
          LivenessPalette.failureAccent);
      expect(
          native('liveness_status_failure_text'), LivenessPalette.failureText);
      expect(native('liveness_status_guidance'), LivenessPalette.guidanceText);
    });

    testWidgets('outcome banner uses the palette of its severity',
        (tester) async {
      final cases = {
        LivenessSeverity.success: [
          LivenessPalette.successText,
          LivenessPalette.successBanner
        ],
        LivenessSeverity.info: [
          LivenessPalette.neutralText,
          LivenessPalette.neutralBanner
        ],
        LivenessSeverity.error: [
          LivenessPalette.failureText,
          LivenessPalette.failureBanner
        ],
      };
      for (final entry in cases.entries) {
        await tester.pumpWidget(MaterialApp(
            home: Scaffold(
                body: LivenessOutcomeBanner(
                    text: 'message', severity: entry.key))));
        final text = tester.widget<Text>(find.text('message'));
        expect(text.style!.color, entry.value[0]);
        final box = tester.widget<Container>(find
            .descendant(
                of: find.byType(LivenessOutcomeBanner),
                matching: find.byType(Container))
            .first);
        expect((box.decoration! as BoxDecoration).color, entry.value[1]);
      }
    });
  });
}
