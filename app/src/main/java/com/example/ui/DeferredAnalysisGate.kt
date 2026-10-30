package com.example.ui

/** Tracks failed attempts without losing a retry requested while an attempt is running. */
class DeferredAnalysisGate {
    private val deferred=mutableSetOf<Int>()
    var generation: Long=0
        private set

    fun isDeferred(id: Int): Boolean = id in deferred
    fun deferAfterFailure(id: Int, attemptGeneration: Long) {
        if(attemptGeneration==generation) deferred.add(id)
    }
    fun retryAll() {
        generation++
        deferred.clear()
    }
}
