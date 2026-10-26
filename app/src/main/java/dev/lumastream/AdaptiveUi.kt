package dev.lumastream

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AppLayout(val isTv: Boolean, val width: Dp, val height: Dp) {
    val compact: Boolean get() = width < 600.dp
    val horizontalPadding: Dp get() = if (isTv) 36.dp else 16.dp
    val verticalPadding: Dp get() = if (isTv) 24.dp else 12.dp
}

val LocalAppLayout = staticCompositionLocalOf { AppLayout(true, 960.dp, 540.dp) }

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val container = if (selected) Color(0xFF425F80) else Color(0xFF283246)
    if (LocalAppLayout.current.isTv) {
        androidx.tv.material3.Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
            colors = androidx.tv.material3.ButtonDefaults.colors(
                containerColor = container,
                contentColor = Color.White,
                focusedContainerColor = Color(0xFF44638A),
                focusedContentColor = Color.White,
            ),
            content = {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides androidx.tv.material3.LocalContentColor.current,
                ) { content() }
            },
        )
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = Color.White,
            ),
            content = content,
        )
    }
}
