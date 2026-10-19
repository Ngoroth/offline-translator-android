package com.example.offlinetranslator.modelsmoke

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Result
import org.junit.runner.notification.RunListener
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.json.JSONArray
import org.json.JSONObject

class EvaluationInstrumentation : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        java.util.Locale.setDefault(java.util.Locale.US)
        val args = arguments ?: Bundle()
        args.getString("runId")?.let { id ->
            require(id.matches(Regex("[a-f0-9-]{36}")))
            require(args.getString("phase") in setOf("regression", "preparation", "feature", "smoke", "candidate", "cleanup"))
            args.putString("listener", EvaluationRunListener::class.java.name)
        }
        super.onCreate(args)
    }
}

class EvaluationRunListener : RunListener() {
    private var ignored = 0
    private val events=JSONArray()
    private fun record(event: String,description: Description?,detail: String? = null) {
        require(events.length()<128)
        events.put(JSONObject().put("event",event).put("test",description?.displayName.orEmpty().take(256))
            .put("detail",detail?.take(2048) ?: JSONObject.NULL).put("atNanos",nowNanos()))
        EvaluationSession.writeStage(JSONObject().put("phase",EvaluationSession.args.getString("phase"))
            .put("candidate",EvaluationSession.args.getString("candidate")).put("events",events))
    }
    override fun testStarted(description: Description?) {record("started",description)}
    override fun testFinished(description: Description?) {record("finished",description)}
    override fun testFailure(failure: Failure) {record("failure",failure.description,failure.trace)}
    override fun testIgnored(description: Description?) { ignored++;record("ignored",description) }
    override fun testRunFinished(result: Result) {
        EvaluationSession.writeStage(JSONObject().put("phase",EvaluationSession.args.getString("phase"))
            .put("candidate",EvaluationSession.args.getString("candidate")).put("events",events)
            .put("run",result.runCount).put("failed",result.failureCount).put("skipped",ignored))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nHARNESS_COUNTS run=${result.runCount} failed=${result.failureCount} skipped=$ignored\n")
        })
    }
}
