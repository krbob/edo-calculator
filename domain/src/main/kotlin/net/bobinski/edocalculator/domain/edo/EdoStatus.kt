package net.bobinski.edocalculator.domain.edo

import kotlinx.serialization.Serializable

@Serializable
enum class EdoStatus {
    ACTIVE,
    MATURED
}
