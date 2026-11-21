package com.healthanalyzer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanContextTest {
    @Test
    fun noPlanMeansNoContext() {
        assertNull(PlanContext.build("(no training plan saved)", "Base block"))
        assertNull(PlanContext.build("  ", ""))
    }

    @Test
    fun summaryComesBeforeTheReview() {
        val text = PlanContext.build("Training plan goal: Sub-50 10K\nPlan vs actual …\n", "Four base weeks, then speed.")!!
        assertEquals("Coach's summary of the plan: Four base weeks, then speed.\n\nTraining plan goal: Sub-50 10K\nPlan vs actual …", text)
        assertEquals("Plan vs actual …", PlanContext.build("Plan vs actual …", " "))
    }

    @Test
    fun detectsPlanPlaceholderInPrompts() {
        assertTrue(PlanContext.inTemplate("Review {{plan_review:8}}", ""))
        assertTrue(PlanContext.inTemplate("", "{{plan_review}}"))
        assertFalse(PlanContext.inTemplate("{{recent_runs:28}}", "{{planning}}"))
    }
}
