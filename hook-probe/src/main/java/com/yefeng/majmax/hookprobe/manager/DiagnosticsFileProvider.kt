package com.yefeng.majmax.hookprobe.manager

import androidx.core.content.FileProvider

/**
 * FileProvider for exported diagnostics archives (`${applicationId}.diagnostics`).
 *
 * Kept a separate class from [UpdatesFileProvider] on purpose: provider instances are
 * keyed by component name (see the note there), so sharing one class across two
 * authorities let the APK-update path config leak into log-archive URIs. A distinct
 * class guarantees this authority always resolves with `@xml/diagnostics_paths`.
 */
class DiagnosticsFileProvider : FileProvider()
