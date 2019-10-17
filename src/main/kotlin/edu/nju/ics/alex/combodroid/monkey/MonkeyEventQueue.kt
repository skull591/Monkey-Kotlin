package edu.nju.ics.alex.combodroid.monkey

import java.util.*
import kotlin.random.Random


/**
 * Class for keeping a monkey event queue
 * */
class MonkeyEventQueue(private val mRandom: Random,  private val mThrottle: Long,
                       private val mRandomizeThrottle: Boolean) : LinkedList<MonkeyEvent>() {
    override fun addLast(p0: MonkeyEvent?) {
        super.addLast(p0!!)
        if (p0.isThrottlable()) {
            var throttle = mThrottle
            if (mRandomizeThrottle && (mThrottle > 0)) {
                throttle = mRandom.nextLong()
                if (throttle < 0) {
                    throttle = - throttle
                }
                throttle %= mThrottle
                ++throttle
            }
            super.add(MonkeyThrottleEvent(throttle))
        }
    }
}