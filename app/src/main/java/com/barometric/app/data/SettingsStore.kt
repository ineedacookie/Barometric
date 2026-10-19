package com.barometric.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.preferences by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val location = stringPreferencesKey("location")
    private val unit = stringPreferencesKey("unit")
    private val steps = booleanPreferencesKey("steps")
    private val sleep = booleanPreferencesKey("sleep")
    private val granted = stringPreferencesKey("health_first_granted")
    private val synced = stringPreferencesKey("health_last_synced")
    private val kpaDefault = booleanPreferencesKey("kpa_default_applied")
    private val symptomWeights = Symptom.entries.associateWith { intPreferencesKey("symptom_weight_${it.name}") }

    val flow = context.preferences.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        AppSettings(
            locationId = p[location] ?: LocationEntity.HYRUM.id,
            unit = if (p[kpaDefault] != true) PressureUnit.KPA else PressureUnit.entries.find { it.name == p[unit] } ?: PressureUnit.KPA,
            syncSteps = p[steps] ?: false, syncSleep = false,
            healthFirstGrantedDate = p[granted], healthLastSyncDate = p[synced],
            symptomWeights = symptomWeights.mapValues { (_, key) -> (p[key] ?: 10).coerceIn(0, 10) },
        )
    }

    suspend fun setLocation(id: String) { context.preferences.edit { it[location] = id } }
    suspend fun setSymptomWeight(symptom: Symptom, weight: Int) {
        require(weight in 0..10)
        context.preferences.edit { it[symptomWeights.getValue(symptom)] = weight }
    }
    suspend fun setUnit(value: PressureUnit) { context.preferences.edit { it[unit] = value.name; it[kpaDefault] = true } }
    suspend fun applyDefaults() { context.preferences.edit { p ->
        if (p[kpaDefault] != true) { p[unit] = PressureUnit.KPA.name; p[kpaDefault] = true }
        p[sleep] = false
    } }
    suspend fun setHealth(readSteps: Boolean? = null, readSleep: Boolean? = null) {
        context.preferences.edit { p ->
            readSteps?.let { p[steps] = it }
            readSleep?.let { p[sleep] = it }
        }
    }
    suspend fun setHealthDates(first: String?, last: String?) {
        context.preferences.edit { p ->
            first?.let { p[granted] = it }
            last?.let { p[synced] = it }
        }
    }
    suspend fun reset() { context.preferences.edit { it.clear() } }
}
