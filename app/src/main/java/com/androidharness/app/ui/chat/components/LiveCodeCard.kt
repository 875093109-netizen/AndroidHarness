package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.CodeDraftStage
import com.androidharness.app.agent.LiveCodeDraft
import com.androidharness.app.agent.LiveCodeLine
import com.androidharness.app.ui.chat.UserScrollObserver
import com.androidharness.app.ui.common.formatTokens
import com.androidharness.app.ui.files.CodeTokenColors
import com.androidharness.app.ui.files.CodeTokenizer
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastEffectsSpec

@Composable
internal fun LiveCodeCard(draft: LiveCodeDraft, awaitingApproval: Boolean = false) {
    var expanded by rememberSaveable(draft.id) { mutableStateOf(true) }
    val scheme = MaterialTheme.colorScheme
    val status = if (awaitingApproval) "Awaiting approval" else draft.stage.label
    Surface(onClick = { expanded = !expanded }, shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live code", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = if (draft.stage == CodeDraftStage.FAILED) scheme.error else scheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.KeyboardArrowDown, if (expanded) "Collapse code preview" else "Expand code preview",
                    Modifier.size(20.dp).rotate(if (expanded) 180f else 0f))
            }
            draft.files.forEach { file ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, fontFamily = HarnessMono,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if ('/' in file.path) Text(file.path, style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(codeLanguage(file.path), style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        AnimatedLineCount(file.added, '+', LocalStatusColors.current.success)
                        AnimatedLineCount(file.removed, '−', scheme.error)
                    }
                }
            }
            Text("≈ ${formatTokens(draft.estimatedTokens)} tool tokens · estimated",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            if (!draft.comparisonAvailable) Text("Generated lines · original file comparison unavailable",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            draft.responseOutputTokens?.let { tokens ->
                Text("Response: ${formatTokens(tokens)} output tokens", style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded, enter = fadeIn(fastEffectsSpec()) + expandVertically(fastEffectsSpec()),
                exit = fadeOut(fastEffectsSpec()) + shrinkVertically(fastEffectsSpec())) {
                val vertical = rememberScrollState()
                val horizontal = rememberScrollState()
                var following by remember(draft.id) { mutableStateOf(true) }
                val observer = remember(draft.id) { UserScrollObserver { following = false } }
                LaunchedEffect(following, draft.stage) {
                    if (following && draft.stage == CodeDraftStage.GENERATING) snapshotFlow { vertical.maxValue }.collect {
                        if (following && !vertical.isScrollInProgress && it != Int.MAX_VALUE) vertical.scrollTo(it)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).nestedScroll(observer)
                            .verticalScroll(vertical).horizontalScroll(horizontal)) {
                            draft.files.forEach { file ->
                                if (draft.files.size > 1) Text(file.path, style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                                file.lines.forEach { CodePreviewLine(it, file.path) }
                            }
                        }
                    }
                    if (draft.limited || draft.files.any { it.lines.size >= 80 }) {
                        Text("Showing a bounded code preview", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    if (draft.stage != CodeDraftStage.SAVED) Text("Draft · not saved yet",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    if (draft.responseOutputTokens != null) Text("Response tokens include reasoning and all generated output.",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AnimatedLineCount(count: Int, sign: Char, color: Color) {
    val displayed by animateIntAsState(count, fastEffectsSpec(), label = "live line count")
    Text("$sign$displayed", style = MaterialTheme.typography.labelMedium, fontFamily = HarnessMono, color = color,
        modifier = Modifier.semantics { contentDescription = "$count lines ${if (sign == '+') "added" else "removed"}" })
}

@Composable
private fun CodePreviewLine(line: LiveCodeLine, path: String) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (line.sign) { '+' -> LocalStatusColors.current.success; '-' -> scheme.error; else -> scheme.onSurface }
    val dark = CodeTokenColors.isDarkSurface(scheme.surface)
    val highlighted = remember(line.text, path, dark, scheme.onSurface) {
        AnnotatedString.Builder(line.text).apply {
            CodeTokenizer.tokenizeLine(CodeTokenizer.langFor(path.lowercase()), line.text).forEach { token ->
                if (token.start >= 0 && token.end <= line.text.length) addStyle(
                    SpanStyle(color = CodeTokenColors.of(token.type, dark, scheme.onSurface)), token.start, token.end)
            }
        }.toAnnotatedString()
    }
    Row(Modifier.fillMaxWidth().background(if (line.sign == ' ') Color.Transparent else tint.copy(alpha = 0.08f))
        .padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(if (line.sign == '-') "− " else "${line.sign} ", color = tint, fontFamily = HarnessMono,
            style = MaterialTheme.typography.bodySmall)
        Text(highlighted, fontFamily = HarnessMono, style = MaterialTheme.typography.bodySmall, softWrap = false)
    }
}

private fun codeLanguage(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "Kotlin"; "java" -> "Java"; "py", "pyw" -> "Python"
    "ts", "tsx" -> "TypeScript"; "js", "jsx", "mjs", "cjs" -> "JavaScript"
    "html", "htm", "vue", "svelte" -> "HTML"; "xml", "svg" -> "XML"
    "css", "scss", "sass", "less" -> "CSS"; "json", "jsonc", "json5" -> "JSON"
    "yml", "yaml" -> "YAML"; "sh", "bash", "zsh" -> "Shell"; "md" -> "Markdown"
    "rs" -> "Rust"; "go" -> "Go"; "c", "h" -> "C"; "cpp", "hpp", "cc" -> "C++"
    else -> "Code"
}
