package io.translatetube.local.translate

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Persists only model limits and tuning statistics. The provider URL is reduced to a hashed
 * origin/path identity; API keys, query strings, prompt text and subtitle content are never saved.
 */
class ApiSubtitleBatchLearningStore(
    context: Context,
    providerKey: String,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val providerFingerprint = sha256(safeProviderIdentity(providerKey))

    internal fun restoreCapability(model: String, providerKey: String) {
        runCatching {
            val json = preferences.getString(key("capability", model), null)
                ?.let(::JSONObject) ?: return
            if (json.optInt("capabilitySchemaVersion", 0) != CAPABILITY_SCHEMA_VERSION) return
            val contextTokens = json.optInt("contextWindowTokens").coerceAtLeast(0)
            val maxOutputTokens = json.optInt("maxOutputTokens").takeIf { it > 0 } ?: return
            val capability = ApiModelCapability(
                contextWindowTokens = contextTokens,
                maxOutputTokens = maxOutputTokens,
                initialRows = json.optInt("initialRows", 30),
                maxRows = json.optInt("maxRows", 80),
                inputBudgetTokens = json.optInt("inputBudgetTokens", 8_000),
                outputBudgetTokens = json.optInt("outputBudgetTokens", 4_096),
                maxInputBudgetTokens = json.optInt(
                    "maxInputBudgetTokens",
                    json.optInt("inputBudgetTokens", 8_000),
                ),
                maxOutputBudgetTokens = json.optInt(
                    "maxOutputBudgetTokens",
                    json.optInt("outputBudgetTokens", 4_096),
                ),
                maxInputTokens = json.optInt("maxInputTokens").takeIf { it > 0 },
            )
            ApiSubtitleBatchPolicy.registerProviderCapability(model, capability, providerKey)
        }
    }

    internal fun saveCapability(model: String, capability: ApiModelCapability) {
        runCatching {
            val json = JSONObject()
                .put("capabilitySchemaVersion", CAPABILITY_SCHEMA_VERSION)
                .put("contextWindowTokens", capability.contextWindowTokens)
                .put("maxOutputTokens", capability.maxOutputTokens)
                .put("initialRows", capability.initialRows)
                .put("maxRows", capability.maxRows)
                .put("inputBudgetTokens", capability.inputBudgetTokens)
                .put("outputBudgetTokens", capability.outputBudgetTokens)
                .put("maxInputBudgetTokens", capability.maxInputBudgetTokens)
                .put("maxOutputBudgetTokens", capability.maxOutputBudgetTokens)
                .put("maxInputTokens", capability.maxInputTokens ?: 0)
            preferences.edit().putString(key("capability", model), json.toString()).apply()
        }
    }

    internal fun loadLearning(
        model: String,
        capability: ApiModelCapability,
        learningScope: String = "",
    ): ApiSubtitleBatchLearningRecord? = runCatching {
            val json = preferences.getString(key("learning", model, learningScope), null)
                ?.let(::JSONObject) ?: return null
            // Discard profiles from earlier recovery policies: their row ceilings and token
            // budgets may have been clamped together by split-leaf outcomes.
            if (json.optInt("recoveryPolicyVersion", 0) != RECOVERY_POLICY_VERSION) return null
            ApiSubtitleBatchLearningRecord(
                maxRows = json.optInt("maxRows", capability.initialRows)
                    .coerceIn(1, capability.maxRows),
                inputTokenMultiplier = json.optDouble("inputTokenMultiplier", 1.0)
                    .coerceIn(0.75, 3.0),
                outputTokenMultiplier = json.optDouble("outputTokenMultiplier", 1.0)
                    .coerceIn(0.75, 3.0),
                inputBudgetTokens = json.optInt("inputBudgetTokens", capability.inputBudgetTokens)
                    .coerceIn(512, capability.maxInputBudgetTokens),
                outputBudgetTokens = json.optInt("outputBudgetTokens", capability.outputBudgetTokens)
                    .coerceIn(512, capability.maxOutputBudgetTokens),
                averageLatencyMs = json.optDouble("averageLatencyMs", 0.0)
                    .coerceIn(0.0, 300_000.0),
            )
        }.getOrNull()

    internal fun saveLearning(
        model: String,
        record: ApiSubtitleBatchLearningRecord,
        learningScope: String = "",
    ) {
        runCatching {
            val json = JSONObject()
                .put("maxRows", record.maxRows)
                .put("inputTokenMultiplier", record.inputTokenMultiplier)
                .put("outputTokenMultiplier", record.outputTokenMultiplier)
                .put("inputBudgetTokens", record.inputBudgetTokens)
                .put("outputBudgetTokens", record.outputBudgetTokens)
                .put("averageLatencyMs", record.averageLatencyMs)
                .put("recoveryPolicyVersion", RECOVERY_POLICY_VERSION)
            preferences.edit().putString(key("learning", model, learningScope), json.toString()).apply()
        }
    }

    private fun key(kind: String, model: String, learningScope: String = ""): String {
        val normalizedModel = model.trim().lowercase(Locale.ROOT)
        val normalizedScope = learningScope.trim().lowercase(Locale.ROOT)
        val identity = if (normalizedScope.isBlank()) {
            "$providerFingerprint|$normalizedModel"
        } else {
            "$providerFingerprint|$normalizedModel|$normalizedScope"
        }
        return "$kind:${sha256(identity)}"
    }

    private fun safeProviderIdentity(raw: String): String =
        ApiSubtitleBatchPolicy.normalizeProviderKey(raw)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val PREFS_NAME = "api_translation_batch_learning"
        const val RECOVERY_POLICY_VERSION = 5
        const val CAPABILITY_SCHEMA_VERSION = 2
    }
}
