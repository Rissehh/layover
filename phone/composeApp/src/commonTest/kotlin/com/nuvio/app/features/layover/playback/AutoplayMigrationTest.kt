package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.streams.StreamAutoPlayMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoplayMigrationTest {
    private var flag: String? = null
    private var mode = StreamAutoPlayMode.MANUAL
    private var sets = 0

    @BeforeTest
    fun setUp() {
        LayoverAutoplayMigration.loadFlag = { flag }
        LayoverAutoplayMigration.saveFlag = { flag = it }
        LayoverAutoplayMigration.currentMode = { mode }
        LayoverAutoplayMigration.setMode = {
            mode = it
            sets++
        }
    }

    @AfterTest
    fun tearDown() {
        LayoverAutoplayMigration.resetForTests()
    }

    @Test
    fun `first launch turns manual picking into first-stream autoplay`() {
        LayoverAutoplayMigration.run()
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, mode)
        assertEquals("true", flag)
    }

    @Test
    fun `a later change back to manual is never overridden`() {
        LayoverAutoplayMigration.run()
        mode = StreamAutoPlayMode.MANUAL
        LayoverAutoplayMigration.run()
        assertEquals(StreamAutoPlayMode.MANUAL, mode)
        assertEquals(1, sets)
    }

    @Test
    fun `nothing changes when the flag cannot be stored`() {
        LayoverAutoplayMigration.saveFlag = { }
        LayoverAutoplayMigration.run()
        LayoverAutoplayMigration.run()
        assertEquals(StreamAutoPlayMode.MANUAL, mode)
        assertEquals(0, sets)
    }

    @Test
    fun `an autoplay mode the user already chose is kept`() {
        mode = StreamAutoPlayMode.REGEX_MATCH
        LayoverAutoplayMigration.run()
        assertEquals(StreamAutoPlayMode.REGEX_MATCH, mode)
        assertEquals(0, sets)
        assertEquals("true", flag)
    }
}
