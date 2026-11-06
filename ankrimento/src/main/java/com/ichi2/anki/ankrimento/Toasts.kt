// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/*
 * The module's one way to show a toast. AnkiDroid's `showThemedToast` (lint: DirectToastMakeTextUsage)
 * is in the app module, which this module cannot reach; the toasts are kept here, in one place.
 */

@SuppressLint("DirectToastMakeTextUsage")
internal fun Context.showToast(
    text: CharSequence,
    long: Boolean = true,
) = Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

@SuppressLint("DirectToastMakeTextUsage")
internal fun Context.showToast(
    @StringRes text: Int,
    long: Boolean = true,
) = Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
