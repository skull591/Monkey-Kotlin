package edu.nju.ics.alex.combodroid.ape

import org.junit.Test

class AndroidDeviceTest {
    @Test
    fun getFocusedStackTest() {
        AndroidDevice.getFocusedStack(
            arrayOf("adb","shell","dumpsys","activity","a")
        )?.dump()
    }
}