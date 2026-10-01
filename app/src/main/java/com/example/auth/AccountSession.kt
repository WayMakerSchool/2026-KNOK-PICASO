package com.example.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The currently selected account for the local Room cache.
 *
 * Room is still the offline-first store, but every row is tagged with this
 * user id once an account is available. This prevents one Google account's
 * records from being shown while another account is active.
 */
object AccountSession {
    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email.asStateFlow()

    fun signedIn(userId: String, email: String?) {
        _userId.value = userId
        _email.value = email
    }

    fun signedOut() {
        _userId.value = null
        _email.value = null
    }
}
