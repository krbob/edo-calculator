package net.bobinski.edocalculator.domain.error

class CpiProviderUnavailableException(
    message: String = "Unable to reach CPI provider.",
    cause: Throwable? = null
) : RuntimeException(message, cause)
