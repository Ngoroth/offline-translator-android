package com.example.offlinetranslator.testkit

/** JNI expects invoke(float[]) returning boxed Integer; an indy lambda has only an erased method. */
class BoundedSynthesisCallback : (FloatArray) -> Int {
    var sampleCount=0
        private set
    var valid=true
        private set
    override fun invoke(samples: FloatArray): Int {
        // Never throw across the native callback: JNI may otherwise abort with a pending exception.
        if(samples.any { !it.isFinite() || it !in -1f..1f }) valid=false
        if(samples.size > 330750-sampleCount) valid=false
        if(valid) sampleCount+=samples.size
        return if(valid) 1 else 0
    }
}
