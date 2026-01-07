package com.gemnote.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ApiClient(private val storage: StorageManager) {
    
    private var api: AnytypeApi? = null
    private var currentBaseUrl: String = ""
    
    fun getApi(): AnytypeApi {
        val baseUrl = storage.baseUrl
        
        if (api == null || currentBaseUrl != baseUrl) {
            currentBaseUrl = baseUrl
            
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            
            val authInterceptor = Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${storage.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            
            val client = OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            
            api = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AnytypeApi::class.java)
        }
        
        return api!!
    }
    
    suspend fun getSpaces(): Result<List<Space>> {
        return try {
            val response = getApi().getSpaces()
            if (response.isSuccessful) {
                Result.success(response.body()?.data ?: emptyList())
            } else {
                Result.failure(Exception("Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun createNote(spaceId: String, title: String, content: String): Result<AnytypeObject> {
        return try {
            val request = CreateObjectRequest(name = title, body = content)
            val response = getApi().createObject(spaceId, request)
            if (response.isSuccessful) {
                val obj = response.body()?.anytypeObject
                if (obj != null) {
                    Result.success(obj)
                } else {
                    Result.failure(Exception("No object returned"))
                }
            } else {
                Result.failure(Exception("Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
