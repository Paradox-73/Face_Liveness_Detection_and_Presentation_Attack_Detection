/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/ui/liveness/model_import_panel.dart';

import 'fake_liveness_service.dart';

Widget _app(Widget child) {
  return MaterialApp(
    locale: const Locale('en'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(body: Center(child: SizedBox(width: 500, child: child))),
  );
}

ModelBundleOutcome _outcome(String status,
    {String version = '', String previous = ''}) {
  return ModelBundleOutcome(
      status: status,
      bundle: 'mosip-liveness-models',
      version: version,
      previousVersion: previous,
      fromStore: status == 'IMPORTED');
}

void main() {
  final button = find.byKey(const ValueKey('liveness-model-import-button'));
  final result = find.byKey(const ValueKey('liveness-model-import-result'));

  Future<FakeLivenessService> open(WidgetTester tester,
      {String? picked = '/sdcard/models.zip'}) async {
    final service = FakeLivenessService();
    await tester.pumpWidget(_app(
        ModelImportPanel(service: service, pickBundle: () async => picked)));
    await tester.pumpAndSettle();
    return service;
  }

  testWidgets('shows the version in use', (tester) async {
    await open(tester);

    expect(find.text('Model in use: version 1.3.0'), findsOneWidget);
    expect(result, findsNothing);
  });

  testWidgets('success: imports the chosen file and shows the new version',
      (tester) async {
    final service = await open(tester);
    service.bundle = _outcome('IMPORTED', version: '1.4.0');

    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(service.importCalls, ['/sdcard/models.zip']);
    expect(find.text('Liveness model 1.4.0 is now in use.'), findsOneWidget);
    expect(find.text('Model in use: version 1.4.0'), findsOneWidget);
  });

  testWidgets('busy: tells the supervisor to finish the capture',
      (tester) async {
    final service = await open(tester);
    service.bundle = _outcome('BUSY');

    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(
        find.text(
            'A face capture is in progress. Finish or close it, then import again.'),
        findsOneWidget);
  });

  testWidgets('too old: names the version that was refused', (tester) async {
    final service = await open(tester);
    service.bundle = _outcome('TOO_OLD', version: '1.2.0', previous: '1.3.0');

    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(
        find.textContaining('Version 1.2.0 is not newer than the model in use'),
        findsOneWidget);
    expect(find.textContaining('Nothing was installed.'), findsOneWidget);
  });

  testWidgets('invalid bundle: says nothing was installed', (tester) async {
    final service = await open(tester);
    service.bundle = _outcome('INVALID');

    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(
        find.text(
            'The bundle failed the signature or file check. Nothing was installed.'),
        findsOneWidget);
  });

  final update = find.byKey(const ValueKey('liveness-model-update-button'));

  testWidgets('online update: hidden while no update URL is synced',
      (tester) async {
    await open(tester);

    expect(update, findsNothing);
  });

  testWidgets('online update: downloads and shows the new version',
      (tester) async {
    final service = FakeLivenessService()..updateConfigured = true;
    await tester.pumpWidget(_app(ModelImportPanel(service: service)));
    await tester.pumpAndSettle();
    service.bundle = _outcome('IMPORTED', version: '1.4.0');

    await tester.tap(update);
    await tester.pumpAndSettle();

    expect(service.updateCalls, 1);
    expect(service.importCalls, isEmpty);
    expect(find.text('Liveness model 1.4.0 is now in use.'), findsOneWidget);
  });

  testWidgets('online update: a failed download is not a missing file',
      (tester) async {
    final service = FakeLivenessService()..updateConfigured = true;
    await tester.pumpWidget(_app(ModelImportPanel(service: service)));
    await tester.pumpAndSettle();
    service.bundle = _outcome('NOT_FOUND');

    await tester.tap(update);
    await tester.pumpAndSettle();

    expect(
        find.textContaining(
            'The model could not be downloaded from the update server.'),
        findsOneWidget);
    expect(find.textContaining('Choose a .zip file'), findsNothing);
  });

  testWidgets('no file chosen: nothing is imported', (tester) async {
    final service = await open(tester, picked: null);

    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(service.importCalls, isEmpty);
    expect(result, findsNothing);
  });
}
