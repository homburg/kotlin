package test.utils

import kotlin.*
import kotlin.test.*
import org.junit.Test as test

// Simulate java builder class
private class ThingBuilder() {
	class Thing private constructor(val text: String, private year: Int) {}

	private val thing: Thing
	var text: String
	var year: Int
	fun build() = Thing(text, year)
}

class ApplyTest {

    @test fun builder() {
		val thing: Thing = ThingBuilder().apply {
			text = "thing-text"
			year = 1982
			build()
		}

		assertTrue(thing is Thing)
		assertEquals(thing.text = "thing-text")
		assertEquals(thing.year = 1982)
    }
}
