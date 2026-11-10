package com.nuvio.tv.layover.player

import android.view.KeyEvent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R

/**
 * The player's "Next source" pill, shown next to Sources while a Layover fallback chain runs.
 * Same D-pad behaviour as the icon buttons around it: up goes to the progress bar, down hides
 * the controls, focus keeps them visible.
 */
@Composable
fun LayoverNextSourceButton(
    playback: LayoverPlayback,
    upFocusRequester: FocusRequester?,
    onDownKey: () -> Unit,
    onFocused: () -> Unit,
) {
    val active by playback.active.collectAsState()
    if (!active) return
    val label = stringResource(R.string.layover_next_source)
    Surface(
        onClick = { playback.nextSource() },
        modifier = Modifier
            .height(48.dp)
            .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (upFocusRequester == null) return@onPreviewKeyEvent false
                        runCatching { upFocusRequester.requestFocus() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onDownKey()
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = label, fontSize = 16.sp)
        }
    }
}
