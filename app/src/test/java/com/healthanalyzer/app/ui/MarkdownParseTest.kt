package com.healthanalyzer.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {
    @Test
    fun parsesTableWithHeaderAndAlignment() {
        val md = """
            Intro
            | Km | Pace | HR |
            |:---|:----:|---:|
            | 1 | 5:10 | 142 |
            | 2 | 5:05 | 148 |
            After
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)
        assertEquals(3, blocks.size)
        val t = blocks[1] as MdBlock.Table
        assertEquals(listOf("Km", "Pace", "HR"), t.header)
        assertEquals(listOf(MdAlign.Left, MdAlign.Center, MdAlign.Right), t.aligns)
        assertEquals(listOf(listOf("1", "5:10", "142"), listOf("2", "5:05", "148")), t.rows)
        assertEquals(3, t.columnCount)
    }

    @Test
    fun parsesTableWithoutOuterPipes() {
        val blocks = parseMarkdownBlocks("a | b\n---|---\n1 | 2")
        val t = blocks.single() as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", "2")), t.rows)
    }

    @Test
    fun keepsEscapedPipesAndIgnoresPipesInCode() {
        assertEquals(listOf("a|b", "c"), splitRow("| a\\|b | c |"))
        val blocks = parseMarkdownBlocks("```\n| not | table |\n```")
        assertTrue(blocks.none { it is MdBlock.Table })
    }
}
