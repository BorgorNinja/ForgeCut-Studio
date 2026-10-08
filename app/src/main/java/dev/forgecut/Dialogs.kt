package dev.forgecut

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TextColors = listOf(
    0xFFFFFFFF, 0xFFFFD60A, 0xFFFF453A, 0xFF30D5C8, 0xFF32D74B, 0xFFFF6BD6, 0xFF000000
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextDialog(
    initial: TextLayer?,
    onDismiss: () -> Unit,
    onConfirm: (text: String, color: Int, size: TextSize, pos: TextPos) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var color by remember { mutableIntStateOf(initial?.color ?: TextColors[0]) }
    var size by remember { mutableStateOf(initial?.size ?: TextSize.MEDIUM) }
    var pos by remember { mutableStateOf(initial?.pos ?: TextPos.BOTTOM) }
    val primary = MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add text" else "Edit text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Your text") }, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextColors.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) primary else Color.Gray, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextSize.entries.forEach {
                        FilterChip(selected = it == size, onClick = { size = it }, label = { Text(it.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextPos.entries.forEach {
                        FilterChip(selected = it == pos, onClick = { pos = it }, label = { Text(it.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim(), color, size, pos) }) {
                Text(if (initial == null) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun TransitionDialog(
    current: TransitionType,
    currentMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (TransitionType, Long) -> Unit,
) {
    var type by remember { mutableStateOf(current) }
    var ms by remember { mutableFloatStateOf(currentMs.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transition") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TransitionType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { type = t }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label)
                    }
                }
                if (type != TransitionType.NONE) {
                    Spacer(Modifier.height(8.dp))
                    Text("Duration ${"%.1f".format(ms / 1000f)} s", style = MaterialTheme.typography.labelLarge)
                    Slider(value = ms, onValueChange = { ms = it }, valueRange = 300f..1500f)
                    Text(
                        "The preview shows a hard cut. The transition appears in the exported video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(type, ms.toLong()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
