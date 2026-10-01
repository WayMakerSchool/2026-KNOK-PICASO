package com.example.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

/** Initializes Firebase only when app/google-services.json was supplied at build time. */
object FirebaseClientProvider {
    @Volatile
    private var cachedApp: FirebaseApp? = null

    fun appOrNull(context: Context): FirebaseApp? {
        cachedApp?.let { return it }
        return synchronized(this) {
            cachedApp ?: runCatching {
                FirebaseApp.getApps(context).firstOrNull()
                    ?: FirebaseApp.initializeApp(context)
            }.getOrNull()?.also {
                AppCheckInstaller.install(it)
                cachedApp = it
            }
        }
    }

    fun isConfigured(context: Context): Boolean = appOrNull(context) != null

    fun authOrNull(context: Context): FirebaseAuth? =
        appOrNull(context)?.let(FirebaseAuth::getInstance)

    fun firestoreOrNull(context: Context): FirebaseFirestore? =
        appOrNull(context)?.let(FirebaseFirestore::getInstance)

    fun storageOrNull(context: Context): FirebaseStorage? =
        appOrNull(context)?.let(FirebaseStorage::getInstance)
}
