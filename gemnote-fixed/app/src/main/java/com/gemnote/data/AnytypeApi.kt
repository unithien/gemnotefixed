package com.gemnote.data

import retrofit2.Response
import retrofit2.http.*

interface AnytypeApi {
    
    @GET("v1/spaces")
    suspend fun getSpaces(): Response<ApiResponse<List<Space>>>
    
    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(
        @Path("spaceId") spaceId: String,
        @Body request: CreateObjectRequest
    ): Response<CreateObjectResponse>
}
