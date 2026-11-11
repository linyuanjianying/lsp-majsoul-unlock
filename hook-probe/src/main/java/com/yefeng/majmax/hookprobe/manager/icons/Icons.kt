/*
 * Copyright 2020 The Android Open Source Project
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
 *   source  : commonMain/androidx/compose/material/icons/Icons.kt
 *             (Icons object shape + materialIcon/materialPath helpers)
 *   upstream: https://cs.android.com/androidx/platform/frameworks/support
 *
 * Trimmed to the two themes this app uses (Filled and AutoMirrored.Filled)
 * and to the helpers needed by the nine icons vendored next to it. The builder
 * defaults (24dp intrinsic size, 24x24 viewport, solid black fill, bevel joins)
 * are copied unchanged so every icon renders exactly as upstream.
 */

package com.yefeng.majmax.hookprobe.manager.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.DefaultFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Local, dependency-free subset of `androidx.compose.material.icons.Icons`.
 *
 * Same shape as upstream, so `Icons.Default.Home` and
 * `Icons.AutoMirrored.Filled.Article` keep working unchanged at call sites.
 */
object Icons {
    /** Baseline (filled) icon theme. */
    object Filled

    /** Alias for [Filled], the baseline icon theme. */
    val Default = Filled

    /** Icon theme whose members mirror for right-to-left locales. */
    object AutoMirrored {
        /** Auto-mirrored filled icon theme. */
        object Filled

        /** Alias for [AutoMirrored.Filled], the baseline auto-mirrored theme. */
        val Default = Filled
    }
}

// All Material icons are 24dp by 24dp, with a viewport size of 24 by 24.
internal const val MaterialIconDimension = 24f

/** Builds a Material icon with the upstream defaults (24dp / 24x24 viewport). */
internal fun materialIcon(
    name: String,
    autoMirror: Boolean = false,
    block: ImageVector.Builder.() -> ImageVector.Builder
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = MaterialIconDimension.dp,
    defaultHeight = MaterialIconDimension.dp,
    viewportWidth = MaterialIconDimension,
    viewportHeight = MaterialIconDimension,
    autoMirror = autoMirror
).block().build()

/** Adds a vector path to this icon with Material defaults. */
internal fun ImageVector.Builder.materialPath(
    fillAlpha: Float = 1f,
    strokeAlpha: Float = 1f,
    pathFillType: PathFillType = DefaultFillType,
    pathBuilder: PathBuilder.() -> Unit
) = path(
    fill = SolidColor(Color.Black),
    fillAlpha = fillAlpha,
    stroke = null,
    strokeAlpha = strokeAlpha,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Butt,
    strokeLineJoin = StrokeJoin.Bevel,
    strokeLineMiter = 1f,
    pathFillType = pathFillType,
    pathBuilder = pathBuilder
)
