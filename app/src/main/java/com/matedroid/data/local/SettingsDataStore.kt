package com.matedroid.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manual override for car image selection.
 *
 * @param variant The model variant (e.g., "my", "myj", "myjs", "myjp")
 * @param wheelCode The wheel code (e.g., "WY18P", "WY19P")
 */
data class CarImageOverride(
    val variant: String,
    val wheelCode: String
) {
    fun toJson(): String = """{"variant":"$variant","wheelCode":"$wheelCode"}"""

    companion object {
        fun fromJson(json: String): CarImageOverride? {
            return try {
                val obj = JSONObject(json)
                CarImageOverride(
                    variant = obj.getString("variant"),
                    wheelCode = obj.getString("wheelCode")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "matedroid_settings")

data class AppSettings(
    val serverUrl: String = "",
    val secondaryServerUrl: String = "",
    val apiToken: String = "",
    val httpBasicAuthUsername: String = "",
    val httpBasicAuthPassword: String = "",
    val acceptInvalidCerts: Boolean = false,
    val currencyCode: String = "EUR",
    val showShortDrivesCharges: Boolean = false,
    val teslamateBaseUrl: String = "",
    val lastSelectedCarId: Int? = null,
    val customHeaders: Map<String, String> = emptyMap()
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    val hasSecondaryServer: Boolean
        get() = secondaryServerUrl.isNotBlank()
}

// Keys for the sensitive fields stored in EncryptedSharedPreferences
private const val ENC_KEY_API_TOKEN = "api_token"
private const val ENC_KEY_AUTH_USERNAME = "http_basic_auth_username"
private const val ENC_KEY_AUTH_PASSWORD = "http_basic_auth_password"
private const val ENC_KEY_CUSTOM_HEADERS = "custom_headers"

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Non-sensitive settings remain in plain DataStore
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val secondaryServerUrlKey = stringPreferencesKey("secondary_server_url")
    private val acceptInvalidCertsKey = booleanPreferencesKey("accept_invalid_certs")
    private val currencyCodeKey = stringPreferencesKey("currency_code")
    private val showShortDrivesChargesKey = booleanPreferencesKey("show_short_drives_charges")
    private val teslamateBaseUrlKey = stringPreferencesKey("teslamate_base_url")
    private val lastSelectedCarIdKey = intPreferencesKey("last_selected_car_id")
    private val carImageOverridesKey = stringPreferencesKey("car_image_overrides")
    private val notificationPermissionAskedKey = booleanPreferencesKey("notification_permission_asked")

    // Legacy DataStore keys — used only during one-time migration, then removed
    private val legacyApiTokenKey = stringPreferencesKey("api_token")
    private val legacyAuthUsernameKey = stringPreferencesKey("http_basic_auth_username")
    private val legacyAuthPasswordKey = stringPreferencesKey("http_basic_auth_password")
    private val legacyCustomHeadersKey = stringPreferencesKey("custom_headers")

    /**
     * Sensitive credentials are stored in EncryptedSharedPreferences, backed by the
     * Android Keystore. The encryption key never leaves the secure hardware enclave.
     */
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "matedroid_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        // Migrate any credentials left in plain DataStore from previous app versions
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            migrateCredentialsIfNeeded()
        }
    }

    val notificationPermissionAsked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[notificationPermissionAskedKey] ?: false
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            serverUrl = preferences[serverUrlKey] ?: "",
            secondaryServerUrl = preferences[secondaryServerUrlKey] ?: "",
            // Read sensitive fields from encrypted storage; fall back to plain DataStore
            // during the brief window before the background migration completes
            apiToken = encryptedPrefs.getString(ENC_KEY_API_TOKEN, null)
                ?: preferences[legacyApiTokenKey] ?: "",
            httpBasicAuthUsername = encryptedPrefs.getString(ENC_KEY_AUTH_USERNAME, null)
                ?: preferences[legacyAuthUsernameKey] ?: "",
            httpBasicAuthPassword = encryptedPrefs.getString(ENC_KEY_AUTH_PASSWORD, null)
                ?: preferences[legacyAuthPasswordKey] ?: "",
            acceptInvalidCerts = preferences[acceptInvalidCertsKey] ?: false,
            currencyCode = preferences[currencyCodeKey] ?: "EUR",
            showShortDrivesCharges = preferences[showShortDrivesChargesKey] ?: false,
            teslamateBaseUrl = preferences[teslamateBaseUrlKey] ?: "",
            lastSelectedCarId = preferences[lastSelectedCarIdKey],
            customHeaders = parseCustomHeadersJson(
                encryptedPrefs.getString(ENC_KEY_CUSTOM_HEADERS, null)
                    ?: preferences[legacyCustomHeadersKey] ?: "{}"
            )
        )
    }

    val showShortDrivesCharges: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[showShortDrivesChargesKey] ?: false
    }

    /**
     * Flow of car image overrides, keyed by car ID.
     */
    val carImageOverrides: Flow<Map<Int, CarImageOverride>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[carImageOverridesKey] ?: "{}"
        parseOverridesJson(jsonString)
    }

    /**
     * One-time migration: if credentials were stored in plain DataStore by an older app version,
     * copy them to EncryptedSharedPreferences and delete the plaintext entries.
     */
    private suspend fun migrateCredentialsIfNeeded() {
        val prefs = context.dataStore.data.first()

        val hasLegacy = listOf(legacyApiTokenKey, legacyAuthUsernameKey,
            legacyAuthPasswordKey, legacyCustomHeadersKey).any { prefs[it] != null }
        if (!hasLegacy) return

        // Write to encrypted storage only if not already migrated
        encryptedPrefs.edit().apply {
            if (!encryptedPrefs.contains(ENC_KEY_API_TOKEN)) {
                putString(ENC_KEY_API_TOKEN, prefs[legacyApiTokenKey] ?: "")
            }
            if (!encryptedPrefs.contains(ENC_KEY_AUTH_USERNAME)) {
                putString(ENC_KEY_AUTH_USERNAME, prefs[legacyAuthUsernameKey] ?: "")
            }
            if (!encryptedPrefs.contains(ENC_KEY_AUTH_PASSWORD)) {
                putString(ENC_KEY_AUTH_PASSWORD, prefs[legacyAuthPasswordKey] ?: "")
            }
            if (!encryptedPrefs.contains(ENC_KEY_CUSTOM_HEADERS)) {
                putString(ENC_KEY_CUSTOM_HEADERS, prefs[legacyCustomHeadersKey] ?: "{}")
            }
        }.apply()

        // Remove plaintext credentials from DataStore
        context.dataStore.edit { p ->
            p.remove(legacyApiTokenKey)
            p.remove(legacyAuthUsernameKey)
            p.remove(legacyAuthPasswordKey)
            p.remove(legacyCustomHeadersKey)
        }
    }

    private fun parseOverridesJson(jsonString: String): Map<Int, CarImageOverride> {
        return try {
            val result = mutableMapOf<Int, CarImageOverride>()
            val obj = JSONObject(jsonString)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val carId = key.toIntOrNull() ?: continue
                val overrideJson = obj.getJSONObject(key)
                val override = CarImageOverride(
                    variant = overrideJson.getString("variant"),
                    wheelCode = overrideJson.getString("wheelCode")
                )
                result[carId] = override
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun overridesToJson(overrides: Map<Int, CarImageOverride>): String {
        val obj = JSONObject()
        for ((carId, override) in overrides) {
            val overrideObj = JSONObject()
            overrideObj.put("variant", override.variant)
            overrideObj.put("wheelCode", override.wheelCode)
            obj.put(carId.toString(), overrideObj)
        }
        return obj.toString()
    }

    private fun parseCustomHeadersJson(jsonString: String): Map<String, String> {
        return try {
            val result = mutableMapOf<String, String>()
            val obj = JSONObject(jsonString)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.getString(key)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun customHeadersToJson(headers: Map<String, String>): String {
        val obj = JSONObject()
        for ((key, value) in headers) {
            obj.put(key, value)
        }
        return obj.toString()
    }

    suspend fun saveSettings(
        serverUrl: String,
        secondaryServerUrl: String,
        apiToken: String,
        httpBasicAuthUsername: String,
        httpBasicAuthPassword: String,
        acceptInvalidCerts: Boolean,
        currencyCode: String,
        customHeaders: Map<String, String> = emptyMap()
    ) {
        // Sensitive fields go to encrypted storage
        encryptedPrefs.edit()
            .putString(ENC_KEY_API_TOKEN, apiToken)
            .putString(ENC_KEY_AUTH_USERNAME, httpBasicAuthUsername)
            .putString(ENC_KEY_AUTH_PASSWORD, httpBasicAuthPassword)
            .putString(ENC_KEY_CUSTOM_HEADERS, customHeadersToJson(customHeaders))
            .apply()

        // Non-sensitive fields go to plain DataStore
        context.dataStore.edit { preferences ->
            preferences[serverUrlKey] = serverUrl
            preferences[secondaryServerUrlKey] = secondaryServerUrl
            preferences[acceptInvalidCertsKey] = acceptInvalidCerts
            preferences[currencyCodeKey] = currencyCode
        }
    }

    suspend fun saveHttpBasicAuth(username: String, password: String) {
        encryptedPrefs.edit()
            .putString(ENC_KEY_AUTH_USERNAME, username)
            .putString(ENC_KEY_AUTH_PASSWORD, password)
            .apply()
        // Touch DataStore to trigger the settings Flow so callers see the updated values
        context.dataStore.edit { }
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[serverUrlKey] = url
        }
    }

    suspend fun saveCurrency(currencyCode: String) {
        context.dataStore.edit { preferences ->
            preferences[currencyCodeKey] = currencyCode
        }
    }

    suspend fun saveShowShortDrivesCharges(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[showShortDrivesChargesKey] = show
        }
    }

    suspend fun saveTeslamateBaseUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[teslamateBaseUrlKey] = url
        }
    }

    suspend fun saveLastSelectedCarId(carId: Int) {
        context.dataStore.edit { preferences ->
            preferences[lastSelectedCarIdKey] = carId
        }
    }

    /**
     * Save or clear a car image override.
     *
     * @param carId The car ID to save the override for
     * @param override The override to save, or null to clear
     */
    suspend fun saveCarImageOverride(carId: Int, override: CarImageOverride?) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[carImageOverridesKey] ?: "{}"
            val currentMap = parseOverridesJson(currentJson).toMutableMap()

            if (override != null) {
                currentMap[carId] = override
            } else {
                currentMap.remove(carId)
            }

            preferences[carImageOverridesKey] = overridesToJson(currentMap)
        }
    }

    suspend fun saveNotificationPermissionAsked() {
        context.dataStore.edit { preferences ->
            preferences[notificationPermissionAskedKey] = true
        }
    }

    suspend fun clearSettings() {
        encryptedPrefs.edit().clear().apply()
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
