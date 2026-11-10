package com.healthanalyzer.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrainingPlanTest {
    private val start = LocalDate.of(2026, 11, 9)
    private val end = LocalDate.of(2026, 11, 22)

    @Test
    fun parsesObjectInsideFencesAndProse() {
        val reply = """
            Here is your plan:
            ```json
            {"summary": "Build **gently**.", "workouts": [
              {"date": "2026-11-12", "type": "tempo", "title": "Tempo 6 km", "distance_km": "6,5 km", "duration_min": 40.4,
               "target": "5'30\"/km · HR 155–165", "notes": "2 km warm-up {then} 3 km steady"},
              {"date": "2026-11-10", "type": "Easy", "title": "", "distance_km": 8, "duration_min": null},
              {"date": "2026-11-11", "type": "rest", "title": "Rest"},
              {"date": "2026-11-15T00:00:00", "type": "long run", "title": "Long 14", "distance_km": 14.0}
            ]}
            ```
            Good luck!
        """.trimIndent()
        val p = TrainingPlan.parse(reply, start, end, nowEpochMs = 5)

        assertEquals("Build **gently**.", p.summary)
        assertEquals(listOf("2026-11-10", "2026-11-12", "2026-11-15"), p.workouts.map { it.date })
        val easy = p.workouts[0]
        assertEquals(WorkoutType.EASY, easy.workoutType)
        assertEquals("Easy run", easy.title)
        assertEquals(8f, easy.distanceKm)
        assertNull(easy.durationMin)
        val tempo = p.workouts[1]
        assertEquals(WorkoutType.TEMPO, tempo.workoutType)
        assertEquals(6.5f, tempo.distanceKm)
        assertEquals(40, tempo.durationMin)
        assertEquals("5'30\"/km · HR 155–165", tempo.target)
        assertEquals(5L, tempo.createdEpochMs)
        assertEquals(WorkoutType.LONG, p.workouts[2].workoutType)
    }

    @Test
    fun acceptsBareArrayAndDropsDatesOutsideThePeriod() {
        val reply = """[
            {"date": "2026-11-08", "type": "easy", "distance_km": 5},
            {"date": "2026-11-09", "type": "intervals", "distance_km": 7},
            {"date": "not a date", "type": "easy"},
            {"date": "2026-11-23", "type": "easy", "distance_km": 5}
        ]"""
        val p = TrainingPlan.parse(reply, start, end)
        assertEquals(listOf("2026-11-09"), p.workouts.map { it.date })
        assertEquals(WorkoutType.INTERVALS, p.workouts.single().workoutType)
        assertEquals("", p.summary)
    }

    @Test
    fun rejectsCutOffOrEmptyReplies() {
        assertThrows(PlanParseException::class.java) {
            TrainingPlan.parse("""{"summary": "x", "workouts": [{"date": "2026-11-10", "type": "easy", "dist""", start, end)
        }
        assertThrows(PlanParseException::class.java) { TrainingPlan.parse("  ", start, end) }
        assertThrows(PlanParseException::class.java) { TrainingPlan.parse("""{"summary": "x", "workouts": []}""", start, end) }
    }

    @Test
    fun promptListsEveryDateWithItsWeekday() {
        val prompt = TrainingPlan.userTemplate(PlanRequest("Sub-55 10 km", 2, 4), start, end)
        assertTrue("Mon 2026-11-09, Tue 2026-11-10" in prompt)
        assertTrue("Sun 2026-11-22\n" in prompt)
        assertTrue("2026-11-23" !in prompt)
        assertTrue("from 2026-11-09 to 2026-11-22 inclusive, with about 4 runs per week" in prompt)
        assertTrue("{{recent_runs:28}}" in prompt && "{{language}}" in prompt)
    }

    @Test
    fun plannedKmSumsTheWeek() {
        val w = listOf(
            PlanWorkoutEntity(date = "2026-11-09", type = "EASY", title = "a", distanceKm = 5f),
            PlanWorkoutEntity(date = "2026-11-15", type = "LONG", title = "b", distanceKm = 12.5f),
            PlanWorkoutEntity(date = "2026-11-16", type = "EASY", title = "c", distanceKm = 6f),
            PlanWorkoutEntity(date = "2026-11-12", type = "CROSS", title = "d"),
        )
        assertEquals(17.5, TrainingPlan.plannedKm(w, start, LocalDate.of(2026, 11, 15)), 1e-6)
    }
}
