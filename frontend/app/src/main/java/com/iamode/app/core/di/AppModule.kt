package com.iamode.app.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.iamode.app.BuildConfig
import com.iamode.app.core.database.IAModeDatabase
import com.iamode.app.core.network.AuthInterceptor
import com.iamode.app.core.network.BaseUrlInterceptor
import com.iamode.app.core.network.IAModeApi
import com.iamode.app.core.security.DatabaseKeyManager
import com.iamode.app.data.gmail.GmailApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context, keys: DatabaseKeyManager): IAModeDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = try {
            keys.passphrase()
        } catch (e: Exception) {
            // Keystore key lost (e.g. after a security update or restore): the old data can't be
            // decrypted, so start fresh instead of crashing on every launch.
            keys.reset()
            context.deleteDatabase(IAModeDatabase.NAME)
            keys.passphrase()
        }
        return Room.databaseBuilder(context, IAModeDatabase::class.java, IAModeDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            // Every schema change must ship a Migration (schemas are exported to app/schemas).
            // Only a downgrade (installing an older build) wipes local data.
            .addMigrations(IAModeDatabase.MIGRATION_1_2, IAModeDatabase.MIGRATION_2_3, IAModeDatabase.MIGRATION_3_4, IAModeDatabase.MIGRATION_4_5, IAModeDatabase.MIGRATION_5_6, IAModeDatabase.MIGRATION_6_7)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides fun conversationDao(db: IAModeDatabase) = db.conversationDao()
    @Provides fun messageDao(db: IAModeDatabase) = db.messageDao()
    @Provides fun contactDao(db: IAModeDatabase) = db.contactDao()
    @Provides fun alertDao(db: IAModeDatabase) = db.alertDao()
    @Provides fun sessionDao(db: IAModeDatabase) = db.sessionDao()
    @Provides fun gmailAccountDao(db: IAModeDatabase) = db.gmailAccountDao()
    @Provides fun mailIntelligenceDao(db: IAModeDatabase) = db.mailIntelligenceDao()
    @Provides fun mailWorkflowDao(db: IAModeDatabase) = db.mailWorkflowDao()
    @Provides fun jobApplicationDao(db: IAModeDatabase) = db.jobApplicationDao()
    @Provides fun outlookAccountDao(db: IAModeDatabase) = db.outlookAccountDao()
    @Provides fun mailActionDao(db: IAModeDatabase) = db.mailActionDao()

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun logging() = HttpLoggingInterceptor().apply {
        // Never log bodies: they contain private messages.
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    @Provides @Singleton @BackendClient
    fun backendHttp(baseUrl: BaseUrlInterceptor, auth: AuthInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrl)
        .addInterceptor(auth)
        .addInterceptor(logging())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // free-tier servers can take ~50 s to wake up
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton @GmailClient
    fun gmailHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging())
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun api(@BackendClient client: OkHttpClient, json: Json): IAModeApi = Retrofit.Builder()
        .baseUrl("https://example.invalid/") // replaced on every request by BaseUrlInterceptor
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(IAModeApi::class.java)

    @Provides @Singleton
    fun gmailApi(@GmailClient client: OkHttpClient, json: Json): GmailApi = Retrofit.Builder()
        .baseUrl("https://gmail.googleapis.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GmailApi::class.java)
}
