package edu.nju.ics.alex.combodroid.monkey

import android.content.ComponentName
import android.graphics.Point
import android.graphics.PointF
import android.hardware.display.DisplayManagerGlobal
import android.os.SystemClock
import android.view.*
import edu.nju.ics.alex.combodroid.utils.Logger
import sun.rmi.runtime.Log
import kotlin.random.Random


/**
 * monkey event queue
 * */
class MonkeySourceRandom(private val mRandom: Random, private val mMainApps: List<ComponentName>,
                         throttle : Long, randomizeThrottle: Boolean, permissionTargetSystem: Boolean) : MonkeyEventSource {



    // static fields and methods
    companion object{
        /** Key events that move around the UI.  */
        private val NAV_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )
        /**
         * Key events that perform major navigation options (so shouldn't be sent as
         * much).
         */
        private val MAJOR_NAV_KEYS = intArrayOf(
            KeyEvent.KEYCODE_MENU, /*
                                                                          * KeyEvent
                                                                          * .
                                                                          * KEYCODE_SOFT_RIGHT,
                                                                          */
          KeyEvent.KEYCODE_DPAD_CENTER
        )
        /** Key events that perform system operations.  */
        private val SYS_KEYS = intArrayOf(
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_BRIGHTNESS_UP,
            KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
            KeyEvent.KEYCODE_SYSRQ
        )
        /** If a physical key exists?  */
        private val PHYSICAL_KEY_EXISTS = BooleanArray(KeyEvent.getMaxKeyCode() + 1){ true }.also { array ->
            // Only examine SYS_KEYS
            SYS_KEYS.forEach{
                array[it] = KeyCharacterMap.deviceHasKey(it);
            }
        }



        /** Possible screen rotation degrees  */
        private val SCREEN_ROTATION_DEGREES =
            intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)

        const val FACTOR_TOUCH = 0
        const val FACTOR_MOTION = 1
        const val FACTOR_PINCHZOOM = 2
        const val FACTOR_TRACKBALL = 3
        const val FACTOR_ROTATION = 4
        const val FACTOR_PERMISSION = 5
        const val FACTOR_NAV = 6
        const val FACTOR_MAJORNAV = 7
        const val FACTOR_SYSOPS = 8
        const val FACTOR_APPSWITCH = 9
        const val FACTOR_FLIP = 10
        const val FACTOR_ANYTHING = 11
        const val FACTORZ_COUNT = 12 // should be last+1

        private const val GESTURE_TAP = 0
        private const val GESTURE_DRAG = 1
        private const val GESTURE_PINCH_OR_ZOOM = 2

        fun getKeyName(keycode: Int) = KeyEvent.keyCodeToString(keycode)
        /**
         * Looks up the keyCode from a given KEYCODE_NAME. NOTE: This may be an
         * expensive operation.
         *
         * @param keyName
         *            the name of the KEYCODE_VALUE to lookup.
         * @returns the intenger keyCode value, or KeyEvent.KEYCODE_UNKNOWN if not
         *          found
         */
        fun getKeyCode(keyName: String) = KeyEvent.keyCodeFromString(keyName)
    }
    // non-static fields

    /**
     * percentages for each type of event. These will be remapped to working
     * values after we read any optional values.
     **/
    private val mFactors = FloatArray(FACTORZ_COUNT)
    private var mEventCount = 0
    private val mQ = MonkeyEventQueue(mRandom, throttle, randomizeThrottle)
    private val mPermissionUtil = MonkeyPermissionUtil().also {
        it.setTargetSystemPackages(permissionTargetSystem)
    }
    private val mThrottle = 0L
    private val mKeyBoardOpen = false
    private var mVerbose = 0

    private var mKeyboardOpen = false

    init {
        // default values for random distributions
        // note, these are straight percentages, to match user input (cmd line
        // args)
        // but they will be converted to 0..1 values before the main loop runs.
        mFactors[FACTOR_TOUCH] = 25.0f
        mFactors[FACTOR_MOTION] = 20.0f
        mFactors[FACTOR_TRACKBALL] = 0f
        // Adjust the values if we want to enable rotation by default.
        mFactors[FACTOR_ROTATION] = 10.0f
        mFactors[FACTOR_NAV] = 5.0f
        mFactors[FACTOR_MAJORNAV] = 5.0f
        mFactors[FACTOR_SYSOPS] = 5.0f
        mFactors[FACTOR_APPSWITCH] = 5.0f
        mFactors[FACTOR_FLIP] = 0f
        // disbale permission by default
        mFactors[FACTOR_PERMISSION] = 5.0f
        mFactors[FACTOR_ANYTHING] = 10.0f
        mFactors[FACTOR_PINCHZOOM] = 10.0f
    }

    /**
     * Adjust the percentages (after applying user values) and then normalize to
     * a 0..1 scale.
     */
    private fun adjustEventFactors(): Boolean {
        // go through all values and compute totals for user & default values
        val (userSum, defaultSum, defaultCount) = mFactors.fold(Triple(0f, 0f, 0)){ (userFold, defaultFold, defaultCount), factor ->
            if (factor <= 0f) { Triple(userFold-factor, defaultFold, defaultCount)}
            else {Triple(userFold, defaultFold+factor, defaultCount+1)}
        }

        // if the user request was > 100%, reject it
        if (userSum > 100f){
            Logger.lPrintln("** Event weights > 100%")
            return false
        }

        //if the user specified all of the weights, they need to be 100%
        if (defaultCount == 0 && (userSum < 99.9f || userSum > 100.1f)) {
            Logger.lPrintln("** Event weight != 100%")
            return false
        }

        // compute the adjustment necessary
        var defaultsTarget = 100f - userSum
        val defaultsAdjustment = defaultsTarget / defaultSum

        //fix all values, by adjusting defaults, or flipping user values back to >0
        for (i in 0 until FACTORZ_COUNT) {
            mFactors[i] = if (mFactors[i] <= 0f) {-mFactors[i]}
            else {mFactors[i] * defaultsAdjustment}
        }

        // if verbose show factors
        if (mVerbose > 0) {
            Logger.lPrintln("// Event percentages:")
            mFactors.forEachIndexed { index, fl ->
                Logger.lPrintln("// $index: $fl%")
            }
        }

        if (!validateKeys()) {
            return false
        }

        //finally, normalize and convert to running sum
        var sum = 0f
        for (i in 0 until FACTORZ_COUNT) {
            sum += mFactors[i] / 100f
            mFactors[i] = sum
        }
        return true
    }

    /**
     * See if any key exists for non-zero factors.
     */
    private fun validateKeys(): Boolean =
         (validateKeyCategory("NAV_KEYS", NAV_KEYS, mFactors[FACTOR_NAV])
                 && validateKeyCategory("MAJOR_NAV_KEYS", MAJOR_NAV_KEYS, mFactors[FACTOR_MAJORNAV])
                 && validateKeyCategory("SYS_KEYS", SYS_KEYS, mFactors[FACTOR_SYSOPS]))

    private fun validateKeyCategory(catName: String, keys: IntArray, factor: Float): Boolean =
        if (factor < 0.1f) {
            true
        } else if (keys.any { PHYSICAL_KEY_EXISTS[it] }) {
            true
        } else {
            Logger.lPrintln("** $catName has no physical keys but with factor $factor%.")
            false
        }

    /**
     * set the factors
     *
     * @param factors
     * percentages for each type of event
     */
    fun setFactors(factors: FloatArray) {
        var c = FACTORZ_COUNT
        if (factors.size < c) {
            c = factors.size
        }
        for (i in 0 until c)
            mFactors[i] = factors[i]
    }
    fun setFactors(index: Int, v: Float) {
        mFactors[index] = v
    }


    /**
     * Generates a random motion event. This method counts a down, move, and up
     * as multiple events.
     *
     * TODO: Test & fix the selectors when non-zero percentages TODO: Longpress.
     * TODO: Fling. TODO: Meta state TODO: More useful than the random walk here
     * would be to pick a single random direction and distance, and divvy it up
     * into a random number of segments. (This would serve to generate fling
     * gestures, which are important).
     *
     * @param random
     *            Random number source for positioning
     * @param gesture
     *            The gesture to perform.
     *
     */
    private fun generatePointerEvent(random: Random, gesture: Int) {
        val display = DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY)

        val p1 = randomPoint(random, display)
        val v1 = randomVector(random)

        val downAt = SystemClock.uptimeMillis()

        mQ.addLast(
            MonkeyTouchEvent(MotionEvent.ACTION_DOWN).setDownTime(downAt).addPointer(0, p1.x, p1.y)
                .setIntermediateNote(false)
        )

        // sometimes we'll move during the touch
        if (gesture == GESTURE_DRAG) {
            repeat(random.nextInt(10)) {
                randomWalk(random, display, p1, v1)

                //println("add MOVE")
                mQ.addLast(
                    MonkeyTouchEvent(MotionEvent.ACTION_MOVE).setDownTime(downAt).addPointer(0, p1.x, p1.y)
                        .setIntermediateNote(true)
                )
               // println((mQ.last as MonkeyMotionEvent).getAction())
            }
        } else if (gesture == GESTURE_PINCH_OR_ZOOM) {
            val p2 = randomPoint(random, display)
            val v2 = randomVector(random)

            randomWalk(random, display, p1, v1)
            mQ.addLast(
                MonkeyTouchEvent(
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                ).setDownTime(downAt)
                    .addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y).setIntermediateNote(true)
            )

            repeat(random.nextInt(10)) {
                randomWalk(random, display, p1, v1)
                randomWalk(random, display, p2, v2)

                mQ.addLast(
                    MonkeyTouchEvent(MotionEvent.ACTION_MOVE).setDownTime(downAt).addPointer(0, p1.x, p1.y)
                        .addPointer(1, p2.x, p2.y).setIntermediateNote(true)
                )
            }

            randomWalk(random, display, p1, v1)
            randomWalk(random, display, p2, v2)
            mQ.addLast(
                MonkeyTouchEvent(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                    .setDownTime(downAt).addPointer(0, p1.x, p1.y).addPointer(1, p2.x, p2.y)
                    .setIntermediateNote(true)
            )
        }

        randomWalk(random, display, p1, v1)
        mQ.addLast(
            MonkeyTouchEvent(MotionEvent.ACTION_UP).setDownTime(downAt).addPointer(0, p1.x, p1.y)
                .setIntermediateNote(false)
        )
    }
    private fun randomPoint(random: Random, display: Display): PointF {
        // since Display.getWidth() is deprecated, use the recommanded way; x is width and y is height
        val size = Point()
        display.getSize(size)
        return PointF(random.nextInt(size.x).toFloat(), random.nextInt(size.y).toFloat())
    }
    private fun randomVector(random: Random) =
        PointF((random.nextFloat() - 0.5f) * 50, (random.nextFloat() - 0.5f) * 50)

    private fun randomWalk(random: Random, display: Display, point: PointF, vector: PointF) {
        val size = Point()
        display.getSize(size)
        point.x = Math.max(Math.min(point.x + random.nextFloat() * vector.x, size.x.toFloat()), 0f)
        point.y = Math.max(Math.min(point.y + random.nextFloat() * vector.y, size.y.toFloat()), 0f)
    }

    /**
     * Generates a random trackball event. This consists of a sequence of small
     * moves, followed by an optional single click.
     *
     * TODO: Longpress. TODO: Meta state TODO: Parameterize the % clicked TODO:
     * More useful than the random walk here would be to pick a single random
     * direction and distance, and divvy it up into a random number of segments.
     * (This would serve to generate fling gestures, which are important).
     *
     * @param random
     * Random number source for positioning
     */
    private fun generateTrackballEvent(random: Random) {
        for (i in 0..9) {
            // generate a small random step
            val dX = random.nextInt(10) - 5
            val dY = random.nextInt(10) - 5

            mQ.addLast(
                MonkeyTrackballEvent(MotionEvent.ACTION_MOVE).addPointer(0, dX.toFloat(), dY.toFloat()).setIntermediateNote(i > 0)
            )
        }

        // 10% of trackball moves end with a click
        if (0 == random.nextInt(10)) {
            val downAt = SystemClock.uptimeMillis()

            mQ.addLast(
                MonkeyTrackballEvent(MotionEvent.ACTION_DOWN).setDownTime(downAt).addPointer(0, 0F, 0F)
                    .setIntermediateNote(true)
            )

            mQ.addLast(
                MonkeyTrackballEvent(MotionEvent.ACTION_UP).setDownTime(downAt).addPointer(0, 0F, 0F)
                    .setIntermediateNote(false)
            )
        }
    }

    /**
     * Generates a random screen rotation event.
     *
     * @param random
     * Random number source for rotation degree.
     */
    private fun generateRotationEvent(random: Random) {
        mQ.addLast(
            MonkeyRotationEvent(
                SCREEN_ROTATION_DEGREES[random.nextInt(SCREEN_ROTATION_DEGREES.size)],
                random.nextBoolean()
            )
        )
    }

    /**
     * generate a random event based on mFactor
     */
    private fun generateEvents() {
        val cls = mRandom.nextFloat()
        var lastKey: Int

        when{
            cls < mFactors[FACTOR_TOUCH] -> {
                println("Touch!")
                generatePointerEvent(mRandom, GESTURE_TAP)
                return
            }
            cls < mFactors[FACTOR_MOTION] -> {
                println("Motion")
                generatePointerEvent(mRandom, GESTURE_DRAG)
                return
            }
            cls < mFactors[FACTOR_PINCHZOOM] -> {
                println("PINCHZOOM")
                generatePointerEvent(mRandom, GESTURE_PINCH_OR_ZOOM)
                return
            }
            cls < mFactors[FACTOR_TRACKBALL] -> {
                println("TrackBall")
                generateTrackballEvent(mRandom)
                return
            }
            cls < mFactors[FACTOR_ROTATION] -> {
                println("Rotation")
                generateRotationEvent(mRandom)
                return
            }
            cls  < mFactors[FACTOR_PERMISSION] -> {
                println("Permission")
                val permissionEvent = mPermissionUtil.generateRandomPermissionEvent(mRandom)
                if (permissionEvent == null) {
                    if (mVerbose > 1) {
                        Logger.lPrintln("WARNING: Unable to generate permission event")
                    }
                    // no permission event can be generated, generate a touch event instead
                    generatePointerEvent(mRandom, GESTURE_TAP)
                    return
                }
                mQ.add(permissionEvent)
                return
            }
        }

        // The remaining event categories are injected as key events
        while (true) {
            when {
                cls < mFactors[FACTOR_NAV] -> lastKey = NAV_KEYS[mRandom.nextInt(NAV_KEYS.size)]
                cls < mFactors[FACTOR_MAJORNAV] -> lastKey = MAJOR_NAV_KEYS[mRandom.nextInt(MAJOR_NAV_KEYS.size)]
                cls < mFactors[FACTOR_SYSOPS] -> lastKey = SYS_KEYS[mRandom.nextInt(SYS_KEYS.size)]
                cls < mFactors[FACTOR_APPSWITCH] -> {
                    println("Activity Switch")
                    val e = MonkeyActivityEvent(mMainApps[mRandom.nextInt(mMainApps.size)])
                    mQ.addLast(e)
                    return
                }
                cls < mFactors[FACTOR_FLIP] -> {
                    println("FLIP")
                    val e = MonkeyFlipEvent(mKeyboardOpen)
                    mKeyboardOpen = !mKeyboardOpen
                    mQ.addLast(e)
                    return
                }
                else -> {
                    lastKey = 1 + mRandom.nextInt(KeyEvent.getMaxKeyCode() - 1)
                }
            }

            if (lastKey != KeyEvent.KEYCODE_POWER && lastKey != KeyEvent.KEYCODE_ENDCALL
                && lastKey != KeyEvent.KEYCODE_SLEEP && PHYSICAL_KEY_EXISTS[lastKey]
            ) {
                break
            }
        }
        println("Key event $lastKey")
        mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_DOWN, mKeyCode = lastKey))
        mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_UP, mKeyCode = lastKey))
    }

    override fun validate(): Boolean {
        var ret = true
        // only populate & dump permissions if enabled
        if (mFactors[FACTOR_PERMISSION] != 0.0f) {
            ret = ret and mPermissionUtil.populatePermissionsMapping()
            if (ret && mVerbose >= 2) {
                mPermissionUtil.dump()
            }
        }
        return ret and adjustEventFactors()
    }

    /**
     * generate an activity event
     */
    fun generateActivity() {
        val e = MonkeyActivityEvent(mMainApps[mRandom.nextInt(mMainApps.size)])
        mQ.addLast(e)
    }

    /**
     * if the queue is empty, we generate events first
     *
     * @return the first event in the queue
     */
    override fun getNextEvent(): MonkeyEvent {
        if (mQ.isEmpty()) {
            generateEvents()
        }
        mEventCount++
        val e = mQ.first
        mQ.removeFirst()
        return e
    }

    override fun setVerbose(verbose: Int) {
        mVerbose = verbose
    }
}