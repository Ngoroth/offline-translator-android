package com.example.offlinetranslator.testkit

import java.util.concurrent.atomic.AtomicInteger

/** Record every arrival, independently of conflated UI state observations. */
class PendingSegments {
    private val pending=AtomicInteger()
    private val maximum=AtomicInteger()
    val count get()=pending.get()
    val peak get()=maximum.get()
    fun admitted() {maximum.accumulateAndGet(pending.incrementAndGet(),::maxOf)}
    fun recognitionStarted() {
        while(true) {
            val current=pending.get();check(current>0) {"Recognition without a captured segment"}
            if(pending.compareAndSet(current,current-1)) return
        }
    }
}
