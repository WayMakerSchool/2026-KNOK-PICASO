package com.example.auth

import android.app.Application
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FirebaseSyncManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthUiState(
    val isConfigured: Boolean,
    val isLoading: Boolean = false,
    val userId: String? = null,
    val email: String? = null,
    val message: String? = null
) {
    val isSignedIn: Boolean
        get() = userId != null
}

/** Google Credential Manager + Firebase Authentication account state. */
class FirebaseAuthViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseClientProvider.authOrNull(application)
    private val syncManager = FirebaseSyncManager.getInstance(application)
    private val _uiState = MutableStateFlow(
        AuthUiState(
            isConfigured = auth != null,
            message = if (auth == null) {
                "Firebase 설정 파일(app/google-services.json)을 추가하면 Google 로그인을 사용할 수 있어."
            } else {
                null
            }
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var lastSyncedUserId: String? = null
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        refreshAccountState(firebaseAuth.currentUser)
    }

    init {
        auth?.addAuthStateListener(authStateListener)
    }

    fun signInWithGoogle(context: Context) {
        val firebaseAuth = auth
        if (firebaseAuth == null) {
            _uiState.update {
                it.copy(message = "app/google-services.json이 없어 Firebase를 시작할 수 없어.")
            }
            return
        }

        val clientIdResource = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        if (clientIdResource == 0) {
            _uiState.update {
                it.copy(message = "Firebase에서 Google 로그인을 활성화한 뒤 최신 google-services.json을 다시 받아줘.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = "Google 계정을 불러오는 중...") }
            runCatching {
                val serverClientId = context.getString(clientIdResource)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val credential = CredentialManager.create(context)
                    .getCredential(context, request)
                    .credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    error("선택한 계정에서 Google ID 토큰을 받지 못했어.")
                }
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(
                    googleCredential.idToken,
                    null
                )
                firebaseAuth.signInWithCredential(firebaseCredential).await()
            }.onSuccess {
                _uiState.update { it.copy(isLoading = false, message = "Google 계정 로그인 완료") }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = error.localizedMessage ?: "Google 로그인에 실패했어."
                    )
                }
            }
        }
    }

    fun signOut(context: Context) {
        auth?.signOut()
        viewModelScope.launch {
            runCatching {
                CredentialManager.create(context)
                    .clearCredentialState(ClearCredentialStateRequest())
            }
            lastSyncedUserId = null
            AccountSession.signedOut()
            _uiState.value = AuthUiState(
                isConfigured = auth != null,
                message = "로그아웃했어. 이 기기의 비회원 로컬 기록만 표시해."
            )
        }
    }

    fun syncNow() {
        if (auth == null || AccountSession.userId.value == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = "기록을 동기화하는 중...") }
            val result = syncManager.syncCurrentUser()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    message = result.fold(
                        onSuccess = { "계정 기록 동기화 완료" },
                        onFailure = { it.localizedMessage ?: "동기화에 실패했어." }
                    )
                )
            }
        }
    }

    private fun refreshAccountState(user: FirebaseUser?) {
        if (user == null) {
            lastSyncedUserId = null
            AccountSession.signedOut()
            _uiState.update { it.copy(isLoading = false, userId = null, email = null) }
            return
        }

        val accountChanged = lastSyncedUserId != user.uid
        AccountSession.signedIn(user.uid, user.email)
        _uiState.update {
            it.copy(
                isLoading = accountChanged,
                userId = user.uid,
                email = user.email
            )
        }

        if (accountChanged) {
            viewModelScope.launch {
                syncManager.saveUserProfile(user)
                val result = syncManager.syncCurrentUser()
                lastSyncedUserId = user.uid
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        message = result.fold(
                            onSuccess = { "Google 계정 로그인 완료 · Firebase 동기화 완료" },
                            onFailure = { "로그인은 완료됐지만 기록 동기화에 실패했어." }
                        )
                    )
                }
            }
        }
    }

    override fun onCleared() {
        auth?.removeAuthStateListener(authStateListener)
        super.onCleared()
    }
}
