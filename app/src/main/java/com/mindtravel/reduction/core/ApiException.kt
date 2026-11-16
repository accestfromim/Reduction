package com.mindtravel.reduction.core

open class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The request never got an answer: no connection, a timeout, or a server too busy to reply. Worth trying again. */
class NetworkException(message: String, cause: Throwable? = null) : ApiException(message, cause)
