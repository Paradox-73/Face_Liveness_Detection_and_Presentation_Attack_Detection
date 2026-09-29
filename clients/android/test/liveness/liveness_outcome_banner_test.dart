import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/ui/liveness/liveness_outcome_banner.dart';
import 'package:registration_client/ui/liveness/liveness_texts.dart';

LivenessOutcome _outcome(String status,
        {String? errorCode, String? message, int used = 1, int max = 3}) =>
    LivenessOutcome(
        status: status,
        errorCode: errorCode,
        message: message,
        attemptsUsed: used,
        maxAttempts: max);

void main() {
  group('LivenessOutcomeBanner', () {
    Future<void> pump(WidgetTester tester, Widget banner) =>
        tester.pumpWidget(MaterialApp(home: Scaffold(body: banner)));

    testWidgets('every severity has its own icon and the text',
        (tester) async {
      final cases = {
        LivenessSeverity.success: Icons.verified_user_outlined,
        LivenessSeverity.info: Icons.info_outline,
        LivenessSeverity.error: Icons.error_outline,
      };
      for (final entry in cases.entries) {
        await pump(tester,
            LivenessOutcomeBanner(text: 'message', severity: entry.key));
        expect(find.byIcon(entry.value), findsOneWidget);
        expect(find.text('message'), findsOneWidget);
      }
    });

    testWidgets('is a live region for screen readers', (tester) async {
      final handle = tester.ensureSemantics();
      await pump(
          tester,
          const LivenessOutcomeBanner(
              text: 'Good, face captured successfully',
              severity: LivenessSeverity.success));
      final semantics = tester.getSemantics(find
          .descendant(
              of: find.byType(LivenessOutcomeBanner),
              matching: find.byType(Semantics))
          .first);
      expect(semantics.hasFlag(SemanticsFlag.isLiveRegion), isTrue);
      handle.dispose();
    });

    test('severity follows the status', () {
      expect(LivenessOutcomeBanner.severityOf(_outcome('PASSED')),
          LivenessSeverity.success);
      expect(LivenessOutcomeBanner.severityOf(_outcome('CANCELLED')),
          LivenessSeverity.info);
      expect(LivenessOutcomeBanner.severityOf(_outcome('FAILED')),
          LivenessSeverity.error);
      expect(LivenessOutcomeBanner.severityOf(_outcome('BLOCKED')),
          LivenessSeverity.error);
    });
  });

  group('liveness texts', () {
    const locales = ['en', 'fr', 'ar', 'hi', 'kn', 'ta'];
    const authCodes = [
      'FACE_NOT_ENROLLED',
      'FACE_MISMATCH',
      'NOT_SUPERVISOR',
      'USER_LOCKED',
      'MAX_ATTEMPTS',
      'LIVENESS_FAILED',
      'ENGINE_UNAVAILABLE',
      'CANCELLED',
      'BUSY',
      'REG_FACE_NOT_VERIFIED',
      'SOMETHING_NEW',
    ];
    const captureStatuses = ['FAILED', 'BLOCKED', 'CANCELLED', 'BUSY', 'X'];

    test('every language has a text for every code and status', () {
      for (final code in locales) {
        final l10n = lookupAppLocalizations(Locale(code));
        expect(l10n.liveness_authenticate_with_face, isNotEmpty);
        expect(l10n.liveness_sign_in_with_face, isNotEmpty);
        expect(l10n.liveness_face_verified, isNotEmpty);
        for (final c in authCodes) {
          expect(l10n.liveness_auth_errors(c), isNotEmpty, reason: '$code $c');
        }
        for (final s in captureStatuses) {
          expect(l10n.liveness_capture_status(s), isNotEmpty,
              reason: '$code $s');
        }
      }
    });

    test('engine message wins over the ARB text', () {
      final l10n = lookupAppLocalizations(const Locale('en'));
      expect(
          faceAuthText(
              l10n,
              _outcome('FAILED',
                  errorCode: 'NOT_SUPERVISOR', message: 'engine text')),
          'engine text');
      expect(faceAuthText(l10n, _outcome('FAILED', errorCode: 'BUSY')),
          'Another face verification is in progress. Close it and try again.');
      expect(faceAuthText(l10n, _outcome('PASSED')), 'Face verified');
    });

    test('capture outcome text and refusal', () {
      final l10n = lookupAppLocalizations(const Locale('en'));
      final blocked = _outcome('BLOCKED', used: 3);
      expect(captureOutcomeText(l10n, blocked),
          startsWith('Face capture could not be completed'));
      expect(captureOutcomeText(l10n, _outcome('PASSED')),
          'Good, face captured successfully');
      expect(isCaptureRefused(blocked), isTrue);
      expect(isCaptureRefused(_outcome('CANCELLED')), isTrue);
      expect(isCaptureRefused(_outcome('PASSED')), isFalse);
      expect(isCaptureRefused(_outcome('NOT_REQUIRED')), isFalse);
    });
  });
}
