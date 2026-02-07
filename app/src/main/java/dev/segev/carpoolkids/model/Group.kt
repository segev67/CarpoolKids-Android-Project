package dev.segev.carpoolkids.model

data class Group(
    val id: String,
    val name: String,
    val inviteCode: String,
    val memberIds: List<String>,
    val createdBy: String
)
