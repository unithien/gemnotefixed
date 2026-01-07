package com.geminianytype.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "gemnote_db").build()
    
    @Provides
    @Singleton
    fun provideClipboardDao(database: AppDatabase): ClipboardDao = database.clipboardDao()
    
    @Provides
    @Singleton
    fun provideOkHttpClient(settingsManager: SettingsManager): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val apiKey = runBlocking { settingsManager.apiKey.first() }
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideAnytypeApi(okHttpClient: OkHttpClient, settingsManager: SettingsManager): AnytypeApi {
        val baseUrl = runBlocking { settingsManager.baseUrl.first().ifEmpty { SettingsManager.DEFAULT_BASE_URL } }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnytypeApi::class.java)
    }
}
