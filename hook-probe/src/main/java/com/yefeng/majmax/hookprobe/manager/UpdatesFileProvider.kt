package com.yefeng.majmax.hookprobe.manager

import androidx.core.content.FileProvider

/**
 * FileProvider for downloaded update packages (`${applicationId}.updates`).
 *
 * This is deliberately a distinct class and not `androidx.core.content.FileProvider`.
 *
 * The framework instantiates providers by component name and caches the resulting
 * `IContentProvider` per *component*, while `<provider android:name>` is what selects
 * the `PathStrategy` used to translate files into content URIs. Two manifest entries
 * that both name `androidx.core.content.FileProvider` therefore collapse onto one
 * registered component (`dumpsys activity providers` lists a single provider whose
 * authorities are both `${applicationId}.updates` and `${applicationId}.diagnostics`):
 * whichever authority resolves first builds the instance, and the second authority is
 * then answered by that same instance with the *wrong* path config. Declaring one
 * subclass per authority keeps the component names distinct, so a diagnostics URI can
 * never be resolved through the update PathStrategy (or vice versa).
 */
class UpdatesFileProvider : FileProvider()
