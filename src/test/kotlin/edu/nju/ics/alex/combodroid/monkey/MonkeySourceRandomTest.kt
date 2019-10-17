package edu.nju.ics.alex.combodroid.monkey

import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class MonkeySourceRandomTest {
    @Test fun adjustEventFactorsTest() {
        val monkeySourceRandom = MonkeySourceRandom(Random(0), listOf(),0,true,true)
        monkeySourceRandom.setVerbose(1)
      //  monkeySourceRandom.adjustEventFactors()
        monkeySourceRandom.setFactors(0, -50.0f)
        monkeySourceRandom.setFactors(1,-60f)
      //  monkeySourceRandom.adjustEventFactors()
    }
}