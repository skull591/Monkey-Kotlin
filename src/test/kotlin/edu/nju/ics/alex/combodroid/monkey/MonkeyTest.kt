package edu.nju.ics.alex.combodroid.monkey

//be careful about the imported library
import org.junit.Assert.*
import org.junit.Test

class MonkeyTest {
    @Test fun testProcessOptions() {
        val monkey = Monkey()
        val args = mutableListOf<String>()
        //empty
        assertEquals(monkey.run(args.toTypedArray()), -1)
        //-s
        args.add("-s")
        args.add("1000")
        monkey.run(args.toTypedArray())
        assertEquals(monkey.mSeed, 1000L)
        args.add("--pct-touch")
        args.add("16")
        monkey.run(args.toTypedArray())

    }

    @Test fun testCreateOutputDirectory() {
        val string = "/nas/juwang/Storage/testCreate1/a"
        val monkey = Monkey()
        repeat(3) {
            monkey.createOutputDirectory(string)
        }
    }
}