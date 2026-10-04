/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';
import 'package:registration_client/ui/liveness/liveness_outcome_banner.dart';

/// Supervisor action "Import liveness model": picks a signed model bundle
/// (a .zip file) and hands it to the native importer, which checks the
/// signature, the file hashes and the version before it switches the engine.
/// Shows the version in use and, after an import, the result in plain words.
///
/// Only a supervisor or administrator may import; for anyone else the native
/// side answers NOT_ALLOWED and the panel shows that.
class ModelImportPanel extends StatefulWidget {
  const ModelImportPanel({super.key, this.service, this.pickBundle});

  /// Service override for tests.
  final LivenessService? service;

  /// Returns the path of the chosen bundle, or null when nothing was chosen.
  /// Defaults to the system file picker (.zip files).
  final Future<String?> Function()? pickBundle;

  @override
  State<ModelImportPanel> createState() => _ModelImportPanelState();
}

class _ModelImportPanelState extends State<ModelImportPanel> {
  LivenessService? _defaultService;
  LivenessService get _service =>
      widget.service ?? (_defaultService ??= LivenessService());
  ModelBundleOutcome? _current;
  ModelBundleOutcome? _result;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _loadCurrent();
  }

  Future<void> _loadCurrent() async {
    final current = await _service.getModelBundle();
    if (!mounted) return;
    setState(() => _current = current);
  }

  Future<String?> _pick() async {
    final picker = widget.pickBundle;
    if (picker != null) {
      return picker();
    }
    final picked = await FilePicker.platform
        .pickFiles(type: FileType.custom, allowedExtensions: ['zip']);
    return picked?.files.single.path;
  }

  Future<void> _import() async {
    final path = await _pick();
    if (path == null || path.isEmpty) return;
    setState(() {
      _busy = true;
      _result = null;
    });
    final result = await _service.importModelBundle(path);
    final current = await _service.getModelBundle();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _result = result;
      _current = current;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final current = _current;
    final result = _result;
    final version = current != null && current.version.isNotEmpty
        ? l10n.liveness_model_current(current.version)
        : l10n.liveness_model_unknown;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(l10n.liveness_model_title,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        Text(version,
            key: const ValueKey('liveness-model-current'),
            style: const TextStyle(fontSize: 14)),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const ValueKey('liveness-model-import-button'),
          onPressed: _busy ? null : _import,
          icon: _busy
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.5))
              : const Icon(Icons.upload_file_outlined),
          label: Text(l10n.liveness_model_import),
        ),
        if (result != null) ...[
          const SizedBox(height: 12),
          LivenessOutcomeBanner(
            key: const ValueKey('liveness-model-import-result'),
            text: l10n.liveness_model_import_result(
                result.status, result.version, result.previousVersion),
            severity: result.status == 'IMPORTED'
                ? LivenessSeverity.success
                : LivenessSeverity.error,
          ),
        ],
      ],
    );
  }
}
