package com.androidharness.app.ui.settings

enum class DiagnosticState(val label: String) {
    READY("Ready"), ISSUE("Needs attention"), OFF("Off"), NOT_CHECKED("Not checked"), CHECKING("Checking…"), INSTALLED("Installed"), REACHABLE("Catalog reachable"),
}

enum class DiagnosticArea(val label: String) {
    PROVIDER("Default provider"), STORAGE("Storage access"), WORKSPACE("Default workspace"), LINUX("Linux tools"), SHIZUKU("Shizuku"), SSH("SSH workspaces"), MCP("Connected tools"), NOTIFICATIONS("Notifications"),
}

enum class CatalogCheck(val detail: String) {
    NOT_CHECKED("Check the model list to test access. Sending a model request is a separate check."),
    CHECKING("Contacting the provider…"),
    REACHABLE("The provider returned models. Model generation and quota have not been tested."),
    EMPTY("The provider returned no models. Check the provider address and account access."),
    AUTH("Sign-in or API key access was rejected. Reconnect or update the key."),
    RATE_LIMIT("The provider is limiting requests. Wait and retry, or choose another provider."),
    FAILED("Could not reach the model list. Check your connection and provider address, then retry."),
    TIMEOUT("The check timed out. Check your connection and retry."),
}

fun catalogFailure(message: String): CatalogCheck = when {
    Regex("\\b(401|403)\\b").containsMatchIn(message) -> CatalogCheck.AUTH
    Regex("\\b429\\b").containsMatchIn(message) -> CatalogCheck.RATE_LIMIT
    else -> CatalogCheck.FAILED
}

fun shizukuDiagnostic(granted: Boolean, ready: Boolean, binding: Boolean, failed: Boolean): DiagnosticState = when {
    !granted -> DiagnosticState.OFF
    ready -> DiagnosticState.READY
    binding -> DiagnosticState.CHECKING
    failed -> DiagnosticState.ISSUE
    else -> DiagnosticState.NOT_CHECKED
}

/** Export a whitelist of states, never configuration, names, paths or raw errors. */
fun diagnosticReport(version: String, androidApi: Int, states: Map<DiagnosticArea, DiagnosticState>,
    providerCount: Int, enabledToolCount: Int): String = buildString {
    appendLine("AndroidHarness troubleshooting")
    // Only a build version's conventional characters may enter the export.
    appendLine("App: ${version.filter { it.isLetterOrDigit() || it in ".-+" }.take(40)}")
    appendLine("Android API: $androidApi")
    appendLine("Scope: default provider and default workspace")
    states.forEach { (area, state) -> appendLine("${area.label}: ${state.label}") }
    appendLine("Saved providers: $providerCount")
    appendLine("Enabled MCP servers: $enabledToolCount")
    appendLine("Catalog access does not confirm model generation or remaining quota.")
    append("Credentials, endpoints, names, file paths, chats and raw logs are excluded.")
}
