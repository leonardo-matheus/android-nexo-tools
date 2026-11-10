package br.com.nexotools.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.nexotools.domain.Calibration
import br.com.nexotools.domain.Vec3
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.nexoPreferences by preferencesDataStore("nexo_preferences")

data class PreferenceState(
    val theme: String = "SYSTEM",
    val decimals: Int = 1,
    val hideSensitive: Boolean = true,
    val onboardingDone: Boolean = false,
    val calibration: Calibration = Calibration(),
    val audioOffsetDb: Double? = null,
    val distanceReferenceDbm: Double = -40.0,
    val distanceExponent: Double = 2.0,
    val externalAutomationConsent:Boolean=false,
    val favorites:Set<String> = emptySet(),
    val primaryRemote: String = "LED",
)

@Singleton
class PreferencesStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.nexoPreferences
    private val theme = stringPreferencesKey("theme")
    private val decimals = intPreferencesKey("decimals")
    private val hide = booleanPreferencesKey("hide_sensitive")
    private val onboarding = booleanPreferencesKey("onboarding_done")
    private val audioOffset = doublePreferencesKey("audio_offset_db")
    private val distanceReference = doublePreferencesKey("distance_reference_dbm")
    private val distanceExponent = doublePreferencesKey("distance_exponent")
    private val externalConsent=booleanPreferencesKey("external_automation_consent")
    private val favorites=stringSetPreferencesKey("favorites")
    private val primaryRemote=stringPreferencesKey("primary_remote")
    private fun key(group: String, axis: String) = doublePreferencesKey("${group}_$axis")
    private fun vector(p: Preferences, group: String, default: Vec3): Vec3 = Vec3(
        p[key(group, "x")]?.takeIf { it.isFinite() } ?: default.x,
        p[key(group, "y")]?.takeIf { it.isFinite() } ?: default.y,
        p[key(group, "z")]?.takeIf { it.isFinite() } ?: default.z,
    )
    val state: Flow<PreferenceState> = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { p ->
        val reference = if (p.contains(key("reference", "x"))) vector(p, "reference", Vec3.ZERO) else null
        val scale = vector(p, "scale", Vec3.ONES).let { value ->
            if (value.x > 0 && value.y > 0 && value.z > 0) value else Vec3.ONES
        }
        PreferenceState(
            p[theme]?.takeIf { it in setOf("SYSTEM", "DARK", "LIGHT") } ?: "SYSTEM",
            (p[decimals] ?: 1).coerceIn(0, 3), p[hide] ?: true, p[onboarding] ?: false,
            Calibration(vector(p, "offset", Vec3.ZERO), scale, vector(p, "gyro_bias", Vec3.ZERO),
                reference?.takeIf { it.x * it.x + it.y * it.y + it.z * it.z > 1e-12 }),
            p[audioOffset]?.takeIf { it.isFinite() }, p[distanceReference]?.takeIf { it.isFinite() } ?: -40.0,
            p[distanceExponent]?.takeIf { it.isFinite() && it > 0 } ?: 2.0,
            p[externalConsent]?:false,
            p[favorites]?:emptySet(),
            p[primaryRemote]?.takeIf { it in setOf("LED", "LG") } ?: "LED",
        )
    }
    suspend fun setTheme(value: String) {
        require(value in setOf("SYSTEM", "DARK", "LIGHT")); store.edit { it[theme] = value }
    }
    suspend fun setDecimals(value: Int) { require(value in 0..3); store.edit { it[decimals] = value } }
    suspend fun setHideSensitive(value: Boolean) { store.edit { it[hide] = value } }
    suspend fun completeOnboarding() { store.edit { it[onboarding] = true } }
    suspend fun setExternalAutomationConsent(value:Boolean){store.edit{it[externalConsent]=value}}
    suspend fun setPrimaryRemote(value:String){require(value in setOf("LED","LG"));store.edit{it[primaryRemote]=value}}
    suspend fun setFavorite(route:String,enabled:Boolean){
        require(route in setOf("wifi","mobile","ping","speed","sonar","level","angle","audio","calibration","remotes","volumeBoost","nightCamera","macros","uninstaller","packets","ports","dnsTls"))
        store.edit { p->val current=p[favorites]?:emptySet();p[favorites]=if(enabled)current+route else current-route }
    }
    suspend fun setAudioOffset(value: Double?) {
        require(value == null || value.isFinite())
        store.edit { if (value == null) it.remove(audioOffset) else it[audioOffset] = value }
    }
    suspend fun setDistanceModel(referenceDbm: Double, exponent: Double) {
        require(referenceDbm.isFinite() && referenceDbm in -100.0..-10.0 && exponent.isFinite() && exponent in 1.0..6.0)
        store.edit { it[distanceReference] = referenceDbm; it[distanceExponent] = exponent }
    }
    suspend fun setCalibration(value: Calibration) {
        require(listOf(value.offset, value.scale, value.gyroBias).all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        require(value.scale.x > 0 && value.scale.y > 0 && value.scale.z > 0)
        value.reference?.let { require(it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.x * it.x + it.y * it.y + it.z * it.z > 1e-12) }
        store.edit { p ->
            fun save(group: String, v: Vec3) { p[key(group, "x")] = v.x; p[key(group, "y")] = v.y; p[key(group, "z")] = v.z }
            save("offset", value.offset); save("scale", value.scale); save("gyro_bias", value.gyroBias)
            if (value.reference != null) save("reference", value.reference) else listOf("x", "y", "z").forEach { p.remove(key("reference", it)) }
        }
    }
    suspend fun resetCalibration() { setCalibration(Calibration()); setAudioOffset(null) }
}
