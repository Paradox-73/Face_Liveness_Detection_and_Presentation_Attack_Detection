import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/ui/liveness/face_auth_panel.dart';

import 'fake_liveness_service.dart';

Widget _app(Widget child, {Locale locale = const Locale('en')}) {
  return MaterialApp(
    locale: locale,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(body: Center(child: SizedBox(width: 500, child: child))),
  );
}

FaceAuthPanel _panel(FakeLivenessService service,
    {List<LivenessOutcome>? results,
    String workflow = 'packet_auth',
    bool showWhenUnavailable = false,
    String languageCode = 'eng'}) {
  return FaceAuthPanel(
    userId: 'officer1',
    workflow: workflow,
    languageCode: languageCode,
    showWhenUnavailable: showWhenUnavailable,
    service: service,
    onResult: (outcome) => results?.add(outcome),
  );
}

void main() {
  final button = find.byKey(const ValueKey('face-auth-button'));
  final outcomeBanner = find.byKey(const ValueKey('face-auth-outcome'));

  testWidgets('hidden when face authentication is not available',
      (tester) async {
    final service = FakeLivenessService(available: false);
    await tester.pumpWidget(_app(_panel(service)));
    await tester.pumpAndSettle();

    expect(button, findsNothing);
  });

  testWidgets('pass: runs the capture in the screen language and reports it',
      (tester) async {
    final service = FakeLivenessService();
    final results = <LivenessOutcome>[];
    await tester.pumpWidget(_app(_panel(service, results: results)));
    await tester.pumpAndSettle();

    expect(find.text('Authenticate with face'), findsOneWidget);
    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(service.languages, ['eng']);
    expect(service.authCalls, ['officer1/packet_auth']);
    expect(results.single.status, 'PASSED');
    expect(find.text('Face verified'), findsOneWidget);
    expect(find.byIcon(Icons.verified_user_outlined), findsOneWidget);
  });

  testWidgets('button is disabled while the capture screen is open',
      (tester) async {
    final service = FakeLivenessService()..gate = Completer<void>();
    await tester.pumpWidget(_app(_panel(service)));
    await tester.pumpAndSettle();

    await tester.tap(button);
    await tester.pump();
    final OutlinedButton widget = tester.widget(button);
    expect(widget.onPressed, isNull);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    service.gate!.complete();
    await tester.pumpAndSettle();
    expect((tester.widget(button) as OutlinedButton).onPressed, isNotNull);
  });

  testWidgets('engine message from the native side is shown as is',
      (tester) async {
    final service = FakeLivenessService(
        next: LivenessOutcome(
            status: 'FAILED',
            errorCode: 'MAX_ATTEMPTS',
            message: 'Face verification could not be completed. Maximum '
                'attempts reached.',
            attemptsUsed: 3,
            maxAttempts: 3));
    await tester.pumpWidget(_app(_panel(service)));
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(
        find.text('Face verification could not be completed. Maximum '
            'attempts reached.'),
        findsOneWidget);
    expect(find.byIcon(Icons.error_outline), findsOneWidget);
  });

  testWidgets('face mismatch without an engine message uses the ARB text',
      (tester) async {
    final service = FakeLivenessService(
        next: LivenessOutcome(
            status: 'FAILED',
            errorCode: 'FACE_MISMATCH',
            attemptsUsed: 1,
            maxAttempts: 3));
    await tester.pumpWidget(_app(_panel(service)));
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(outcomeBanner, findsOneWidget);
    expect(find.text('The face does not match this user. Please try again.'),
        findsOneWidget);
  });

  testWidgets(
      'supervisor option stays visible for a non-supervisor while liveness is on',
      (tester) async {
    final service = FakeLivenessService(available: false, enforced: true);
    await tester.pumpWidget(_app(
        _panel(service, workflow: 'eod_auth', showWhenUnavailable: true)));
    await tester.pumpAndSettle();
    expect(button, findsOneWidget);

    final off = FakeLivenessService(available: false, enforced: false);
    await tester.pumpWidget(_app(
        _panel(off, workflow: 'exception_auth', showWhenUnavailable: true)));
    await tester.pumpAndSettle();
    expect(button, findsNothing);
  });

  testWidgets('Arabic: translated label and right-to-left layout',
      (tester) async {
    final service = FakeLivenessService();
    await tester.pumpWidget(_app(_panel(service, languageCode: 'ara'),
        locale: const Locale('ar')));
    await tester.pumpAndSettle();

    expect(find.text('المصادقة بالوجه'), findsOneWidget);
    expect(Directionality.of(tester.element(button)), TextDirection.rtl);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(service.languages, ['ara']);
    expect(find.text('تم التحقق من الوجه'), findsOneWidget);
  });
}
