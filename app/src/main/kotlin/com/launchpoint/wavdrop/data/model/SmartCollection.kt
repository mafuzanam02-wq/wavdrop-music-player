package com.launchpoint.wavdrop.data.model

data class SmartCollection(
    val id: String,
    val title: String,
    val description: String,
    val type: SmartCollectionType,
    val songCount: Int,
)
