package edu.nju.ics.alex.combodroid.monkey

import android.app.IActivityManager
import android.view.IWindowManager
import java.lang.IllegalStateException

abstract class MonkeyEvent(var eventType: Int) {
    companion object {
        val EVENT_TYPE_KEY = 0
        val EVENT_TYPE_TOUCH = 1
        val EVENT_TYPE_TRACKBALL = 2
        val EVENT_TYPE_ROTATION = 3 // Screen rotation
        val EVENT_TYPE_ACTIVITY = 4
        val EVENT_TYPE_FLIP = 5 // Keyboard flip
        val EVENT_TYPE_THROTTLE = 6
        val EVENT_TYPE_PERMISSION = 7
        val EVENT_TYPE_NOOP = 8

        val INJECT_SUCCESS = 1
        val INJECT_FAIL = 0

        // error code for remote exception during injection
        val INJECT_ERROR_REMOTE_EXCEPTION = -1
        // error code for security exception during injection
        val INJECT_ERROR_SECURITY_EXCEPTION = -2
    }

    var eventId = -1
        set(value) {
            if (field != -1) {throw IllegalStateException()}
            field = value
        }

//    /**
//     * @return event type
//     */
//    fun getEventTypePub() =  eventType /* Important: name changed due to clash in Kotlin*/

    /**
     * @return true if it is safe to throttle after this event, and false
     * otherwise.
     */
    open fun isThrottlable() = true

    /**
     * a method for injecting event
     *
     * @param iwm
     * wires to current window manager
     * @param iam
     * wires to current activity manager
     * @param verbose
     * a log switch
     * @return INJECT_SUCCESS if it goes through, and INJECT_FAIL if it fails in
     * the case of exceptions, return its corresponding error code
     */
    abstract fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int
}