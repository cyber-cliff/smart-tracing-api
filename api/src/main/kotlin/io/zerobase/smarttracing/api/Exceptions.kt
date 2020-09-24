package io.zerobase.smarttracing.api

class InvalidPhoneNumberException(message: String) : Exception(message)

class EntityCreationException(message: String? = null, cause: Throwable? = null): Exception(message, cause)

class InvalidIdException(val id: String): Exception("The provided id [$id] was not found.")

class UpdateFailedException(updateDescription: String, cause: Throwable? = null):
    Exception("Failed to execute update: $updateDescription", cause)

class QueryFailedException(queryDescription: String, cause: Throwable? = null):
    Exception("Failed to execute query: $queryDescription", cause)
