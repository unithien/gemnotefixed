package com.gemnote.data

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(val data: T?)

data class Space(
    val id: String,
    val name: String,
    val icon: String?
)

data class AnytypeObject(
    val id: String,
    val name: String,
    val icon: String?,
    @SerializedName("type_key")
    val typeKey: String?,
    val snippet: String?
)

data class CreateObjectRequest(
    val name: String,
    val body: String,
    val icon: String = "🤖",
    @SerializedName("type_key")
    val typeKey: String = "ot-note"
)

data class CreateObjectResponse(
    @SerializedName("object")
    val anytypeObject: AnytypeObject?
)
