/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ---------------------------------------------------------------------------
 * Vendored from AndroidX Material Icons, licensed under Apache License 2.0.
 *   artifact: vendored Material Icons 1.7.6 source (Apache-2.0)
 *   source  : icons/filled/Home.kt
 *   upstream: https://cs.android.com/androidx/platform/frameworks/support
 *
 * Only the package declaration, the androidx icon helper imports and the
 * redundant 'public' modifier were adapted; path data, the 24dp intrinsic size
 * and the autoMirror flag are unchanged.
 */

package com.yefeng.majmax.hookprobe.manager.icons

import androidx.compose.ui.graphics.vector.ImageVector

val Icons.Filled.Home: ImageVector
    get() {
        if (_home != null) {
            return _home!!
        }
        _home = materialIcon(name = "Filled.Home") {
            materialPath {
                moveTo(10.0f, 20.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineToRelative(3.0f)
                lineTo(12.0f, 3.0f)
                lineTo(2.0f, 12.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(8.0f)
                close()
            }
        }
        return _home!!
    }

private var _home: ImageVector? = null
