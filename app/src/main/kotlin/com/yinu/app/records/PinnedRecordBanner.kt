package com.yinu.app.records

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haooz.chedule.ui.utils.rememberDefaultEdgeLight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.edgelight.edgeLight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.data.Record
import com.yinu.designsystem.YinuMenuIcons

internal val PinnedRecordHeight = 48.dp
private val PinShape = ContinuousRoundedRectangle(18.dp)
private val PinGlass: BackdropEffectScope.() -> Unit = {
    vibrancy()
    blur(16.dp.toPx())
}

@Composable
internal fun PinnedRecordBanner(
    record: Record,
    backdrop: Backdrop,
    onLocate: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val glass = isRenderEffectSupported()
    val surface = colors.surface.copy(alpha = if (glass) 0.76f else 1f)
    Row(
        modifier.height(PinnedRecordHeight)
            .then(
                if (glass) Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { PinShape },
                    effects = PinGlass,
                    highlight = null,
                    shadow = null,
                ) else Modifier,
            )
            .edgeLight(shape = PinShape, edgeLight = rememberDefaultEdgeLight(degradedBaseColor = colors.surface))
            .clip(PinShape)
            .background(surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "定位原消息",
                    onClick = onLocate,
                )
                .semantics { contentDescription = "置顶消息" }
                .padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).background(colors.primary.copy(alpha = 0.11f), ContinuousCapsule()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(YinuMenuIcons.pin, null, Modifier.size(17.dp), tint = colors.primary)
            }
            Text(
                record.previewSummary(),
                modifier = Modifier.weight(1f),
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(YinuMenuIcons.open, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        }
        Box(
            Modifier.width(44.dp).fillMaxHeight()
                .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onUnpin),
            contentAlignment = Alignment.Center,
        ) {
            Icon(YinuMenuIcons.close, "取消置顶消息", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
    }
}
