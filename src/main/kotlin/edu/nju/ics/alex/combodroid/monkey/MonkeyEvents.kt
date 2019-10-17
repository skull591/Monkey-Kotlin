package edu.nju.ics.alex.combodroid.monkey

import android.app.IActivityManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.display.DisplayManagerGlobal
import android.hardware.input.InputManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.util.SparseArray
import android.view.*
import edu.nju.ics.alex.combodroid.utils.Logger
import java.io.*
import java.lang.Process
import java.util.ArrayList
import java.util.regex.Pattern


/**
 * set of all Monkey event classes
 * */

class MonkeyActivityEvent(private val mApp: ComponentName, public val mAlarmTime: Long = 0) : MonkeyEvent(EVENT_TYPE_ACTIVITY) {

    /**
     * @return Intent for the new activity
     */
    /* private */ internal fun getEvent(): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.component = mApp
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return intent
    }

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        val intent = getEvent()
        if (verbose > 0) {
            Logger.lPrintln(":Switch ${intent.toUri(0)}")
        }
        if (mAlarmTime != 0L) {
            val args = Bundle()
            args.putLong("alarmTime", mAlarmTime)
            intent.putExtras(args)
        }

        try {
            iam.startActivity(null,null,intent,null,null,null,0,0,null,null)
        } catch (e: RemoteException){
            Logger.lPrintln("** Failed talking with activity manager!")
            return MonkeyEvent.INJECT_ERROR_REMOTE_EXCEPTION
        } catch (e: SecurityException) {
            if (verbose > 0) {
                Logger.lPrintln("** Permissions error starting activity ${intent.toUri(0)}")
            }
            return INJECT_ERROR_SECURITY_EXCEPTION
        }
        return INJECT_SUCCESS
    }

}

class MonkeyCommandEvent(private val mCmd: String?) : MonkeyEvent(EVENT_TYPE_ACTIVITY) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        if (mCmd != null) {
            // Execute the shell command
            try {
                val p = Runtime.getRuntime().exec(mCmd)
                val status = p.waitFor()
                System.err.println("// Shell command $mCmd status was $status")
            } catch (e: Exception) {
                System.err.println("// Exception from $mCmd:")
                System.err.println(e.toString())
            }

        }
        return INJECT_SUCCESS
    }
}

class MonkeyFlipEvent(private val mKeyboardOpen: Boolean) : MonkeyEvent(EVENT_TYPE_FLIP) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        if (verbose > 0) {
            println(":Sending Flip keyboardOpen=$mKeyboardOpen")
        }

        // inject flip event
        return try {
            val f = FileOutputStream("/dev/input/event0")
            f.write(if (mKeyboardOpen) FLIP_0 else FLIP_1)
            f.close()
            INJECT_SUCCESS
        } catch (e: IOException) {
            println("Got IOException performing flip: $e")
            INJECT_FAIL
        }

    }

    companion object {

        // Raw keyboard flip event data
        // Works on emulator and dream

        private val FLIP_0 = byteArrayOf(
            0x7f,
            0x06,
            0x00,
            0x00,
            0xe0.toByte(),
            0x39,
            0x01,
            0x00,
            0x05,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00
        )

        private val FLIP_1 = byteArrayOf(
            0x85.toByte(),
            0x06,
            0x00,
            0x00,
            0x9f.toByte(),
            0xa5.toByte(),
            0x0c,
            0x00,
            0x05,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
    }
}
//Base class for implementing application instrumentation code. When running with instrumentation turned on, this class will be instantiated for you before any of the application code, allowing you to monitor all of the interaction the system has with the application. An Instrumentation implementation is described to the system through an AndroidManifest.xml's <instrumentation> tag.
class MonkeyInstrumentationEvent(internal var mTestCaseName: String?, internal var mRunnerName: String) :
    MonkeyEvent(EVENT_TYPE_ACTIVITY) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        val cn = ComponentName.unflattenFromString(mRunnerName)
        require(!(cn == null || mTestCaseName == null)) { "Bad component name" }

        val args = Bundle()
        args.putString("class", mTestCaseName)
        try {
            iam.startInstrumentation(cn, null, 0, args, null, null, 0, null)
        } catch (e: RemoteException) {
            System.err.println("** Failed talking with activity manager!")
            return MonkeyEvent.INJECT_ERROR_REMOTE_EXCEPTION
        }

        return MonkeyEvent.INJECT_SUCCESS
    }
}


class MonkeyKeyEvent(private var mDownTime : Long = -1, private var mEventTime : Long = -1,  private val mAction : Int,
                     private val mKeyCode: Int, private val mRepeatCount : Int = 0, private val mMetaState : Int = 0,
                     private val mDeviceId : Int = KeyCharacterMap.VIRTUAL_KEYBOARD, private val mScanCode : Int = 0,
                     private val mKeyEvent : KeyEvent? = null) : MonkeyEvent(EVENT_TYPE_KEY) {
    constructor(e: KeyEvent) : this(mAction = -1, mKeyCode = -1, mKeyEvent = e)

    fun getKeyCode() = mKeyEvent?.keyCode ?: mKeyCode
    fun getAction() = mKeyEvent?.action ?: mAction
    fun getDownTime() = mKeyEvent?.downTime ?: mDownTime
    fun getEventTime() = mKeyEvent?.eventTime ?: mEventTime

    fun setDownTime(downTime: Long) {
        check(mKeyEvent == null) { "Cannot modify down time of this key event." }
        mDownTime = downTime
    }

    fun setEventTime(eventTime: Long) {
        check(mKeyEvent == null) { "Cannot modify event time of this key event." }
        mEventTime = eventTime
    }

    override fun isThrottlable() = getAction() == KeyEvent.ACTION_UP

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        if (verbose > 1) {
            val note = if (mAction == KeyEvent.ACTION_UP) {"ACTION_UP"}
            else { "ACTION_DOWN"}
            try {
                Logger.lPrintln(":Sending Key ($note): $mKeyCode //${MonkeySourceRandom.getKeyName(mKeyCode)}")
            } catch (e: ArrayIndexOutOfBoundsException) {
                Logger.lPrintln(":Sending Key ($note): $mKeyCode // Unknown key event")
            }
        }

        val keyEvent = if (mKeyEvent == null) {
            val eventTime = if (mEventTime <=0) SystemClock.uptimeMillis() else mEventTime
            val downTime = if (mDownTime <= 0) eventTime else mDownTime
            KeyEvent(downTime, eventTime, mAction, mKeyCode, mRepeatCount, mMetaState, mDeviceId, mScanCode, KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD)
        } else mKeyEvent
        if (!InputManager.getInstance().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)) {
            return INJECT_FAIL
        }
        return INJECT_SUCCESS
    }
}


/**
 * monkey noop event (don't do anything).
 */
class MonkeyNoopEvent : MonkeyEvent(MonkeyEvent.EVENT_TYPE_NOOP) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        // No real work to do
        if (verbose > 1) {
            println("NOOP")
        }
        return MonkeyEvent.INJECT_SUCCESS
    }
}


/**
 * Events for running a special shell command to capture the frame rate for a
 * given app. To run this test, the system property
 * viewancestor.profile_rendering must be set to true to force the currently
 * focused window to render at 60 Hz.
 *
 * Basically, use dumpsys gfxinfo $ActivityName to get the No. of the first frame and the last one rendered during
 * a time period, and calculate the
 */
class MonkeyGetAppFrameRateEvent : MonkeyEvent {

    private val GET_APP_FRAMERATE_TMPL = "dumpsys gfxinfo %s"
    private var mStatus: String? = null

    constructor(status: String, activityName: String, testCaseName: String) : super(EVENT_TYPE_ACTIVITY) {
        mStatus = status
        sActivityName = activityName
        sTestCaseName = testCaseName
    }

    constructor(status: String, activityName: String) : super(EVENT_TYPE_ACTIVITY) {
        mStatus = status
        sActivityName = activityName
    }

    constructor(status: String) : super(EVENT_TYPE_ACTIVITY) {
        mStatus = status
    }

    // Calculate the average frame rate
    private fun getAverageFrameRate(totalNumberOfFrame: Int, duration: Float): Float {
        var avgFrameRate = 0f
        if (duration > 0) {
            avgFrameRate = totalNumberOfFrame / duration
        }
        return avgFrameRate
    }

    /**
     * Calculate the frame rate and write the output to a file on the SD card.
     */
    private fun writeAverageFrameRate() {
        var writer: FileWriter? = null
        val avgFrameRate: Float
        var totalNumberOfFrame : Int
        try {
            Log.w(TAG, "file: $LOG_FILE")
            writer = FileWriter(LOG_FILE, true) // true = append
            totalNumberOfFrame = sEndFrameNo - sStartFrameNo
            avgFrameRate = getAverageFrameRate(totalNumberOfFrame, sDuration)
            writer.write(String.format("%s:%.2f\n", sTestCaseName, avgFrameRate))
        } catch (e: IOException) {
            Log.w(TAG, "Can't write sdcard log file", e)
        } finally {
            try {
                writer?.close()
            } catch (e: IOException) {
                Log.e(TAG, "IOException $e")
            }

        }
    }

    // Parse the output of the dumpsys shell command call
    @Throws(IOException::class)
    private fun getNumberOfFrames(reader: BufferedReader): String? {
        var noOfFrames: String? = null
        var line: String? = reader.readLine()
        while ( line != null) {
            val m = NO_OF_FRAMES_PATTERN.matcher(line)
            if (m.matches()) {
                noOfFrames = m.group(1)
                break
            }
            line = reader.readLine()
        }
        return noOfFrames
    }

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        var p: Process? = null
        var result: BufferedReader? = null
        val cmd = String.format(GET_APP_FRAMERATE_TMPL, sActivityName)
        try {
            p = Runtime.getRuntime().exec(cmd)
            val status = p!!.waitFor()
            if (status != 0) {
                System.err.println(String.format("// Shell command %s status was %s", cmd, status))
            }
            result = BufferedReader(InputStreamReader(p.inputStream))

            val output = getNumberOfFrames(result)

            if (output != null) {
                if ("start" == mStatus) {
                    sStartFrameNo = Integer.parseInt(output)
                    sStartTime = System.currentTimeMillis()
                } else if ("end" == mStatus) {
                    sEndFrameNo = Integer.parseInt(output)
                    sEndTime = System.currentTimeMillis()
                    val diff = sEndTime - sStartTime
                    sDuration = (diff / 1000.0).toFloat()
                    writeAverageFrameRate()
                }
            }
        } catch (e: Exception) {
            System.err.println("// Exception from $cmd:")
            System.err.println(e.toString())
        } finally {
            try {
                result?.close()
                p?.destroy()
            } catch (e: IOException) {
                System.err.println(e.toString())
            }

        }
        return MonkeyEvent.INJECT_SUCCESS
    }

    companion object {
        private var sStartTime: Long = 0 // in millisecond
        private var sEndTime: Long = 0 // in millisecond
        private var sDuration: Float = 0.toFloat() // in seconds
        private var sActivityName: String? = null
        private var sTestCaseName: String? = null
        private var sStartFrameNo: Int = 0
        private var sEndFrameNo: Int = 0

        private val TAG = "MonkeyGetAppFrameRateEvent"
        private val LOG_FILE = File(Environment.getExternalStorageDirectory(), "avgAppFrameRateOut.txt")
            .absolutePath
        private val NO_OF_FRAMES_PATTERN = Pattern.compile(".* ([0-9]*) frames rendered")
    }
}

/**
 * Events for running a special shell command to capture the frame rate. To run
 * this test, the system property viewancestor.profile_rendering must be set to
 * true to force the currently focused window to render at 60 Hz.
 *
 * Not specifically attached to a certain app. using command service call SurfaceFlinger 1013
 */
class MonkeyGetFrameRateEvent : MonkeyEvent {

    private val GET_FRAMERATE_CMD = "service call SurfaceFlinger 1013"
    private var mStatus: String? = null

    constructor(status: String, testCaseName: String) : super(EVENT_TYPE_ACTIVITY) {
        mStatus = status
        mTestCaseName = testCaseName
    }

    constructor(status: String) : super(EVENT_TYPE_ACTIVITY) {
        mStatus = status
    }

    // Calculate the average frame rate
    private fun getAverageFrameRate(totalNumberOfFrame: Int, duration: Float): Float {
        var avgFrameRate = 0f
        if (duration > 0) {
            avgFrameRate = totalNumberOfFrame / duration
        }
        return avgFrameRate
    }

    /**
     * Calculate the frame rate and write the output to a file on the SD card.
     */
    private fun writeAverageFrameRate() {
        var writer: FileWriter? = null
        val avgFrameRate: Float
        var totalNumberOfFrame : Int
        try {
            writer = FileWriter(LOG_FILE, true) // true = append
            totalNumberOfFrame = mEndFrameNo - mStartFrameNo
            avgFrameRate = getAverageFrameRate(totalNumberOfFrame, mDuration)
            writer.write(String.format("%s:%.2f\n", mTestCaseName, avgFrameRate))
            writer.close()
        } catch (e: IOException) {
            Log.w(TAG, "Can't write sdcard log file", e)
        } finally {
            try {
                writer?.close()
            } catch (e: IOException) {
                Log.e(TAG, "IOException $e")
            }

        }
    }

    // Parse the output of the surfaceFlinge shell command call
    private fun getNumberOfFrames(input: String): String? {
        var noOfFrames: String? = null
        val m = NO_OF_FRAMES_PATTERN.matcher(input)
        if (m.matches()) {
            noOfFrames = m.group(1)
        }
        return noOfFrames
    }

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        var p: java.lang.Process? = null
        var result: BufferedReader? = null
        try {
            p = Runtime.getRuntime().exec(GET_FRAMERATE_CMD)
            val status = p!!.waitFor()
            if (status != 0) {
                System.err.println(String.format("// Shell command %s status was %s", GET_FRAMERATE_CMD, status))
            }
            result = BufferedReader(InputStreamReader(p.inputStream))

            // Only need the first line of the output
            val output = result.readLine()

            if (output != null) {
                if (mStatus == "start") {
                    mStartFrameNo = Integer.parseInt(getNumberOfFrames(output), 16)
                    mStartTime = System.currentTimeMillis()
                } else if (mStatus == "end") {
                    mEndFrameNo = Integer.parseInt(getNumberOfFrames(output), 16)
                    mEndTime = System.currentTimeMillis()
                    val diff = mEndTime - mStartTime
                    mDuration = (diff / 1000.0).toFloat()
                    writeAverageFrameRate()
                }
            }
        } catch (e: Exception) {
            System.err.println("// Exception from $GET_FRAMERATE_CMD:")
            System.err.println(e.toString())
        } finally {
            try {
                result?.close()
                p?.destroy()
            } catch (e: IOException) {
                System.err.println(e.toString())
            }

        }
        return MonkeyEvent.INJECT_SUCCESS
    }

    companion object {
        private var mStartTime: Long = 0 // in millisecond
        private var mEndTime: Long = 0 // in millisecond
        private var mDuration: Float = 0.toFloat() // in seconds
        private var mTestCaseName: String? = null
        private var mStartFrameNo: Int = 0
        private var mEndFrameNo: Int = 0

        private val TAG = "MonkeyGetFrameRateEvent"
        private val LOG_FILE = "/sdcard/avgFrameRateOut.txt"

        private val NO_OF_FRAMES_PATTERN = Pattern.compile(".*\\(([a-f[A-F][0-9]].*?)\\s.*\\)")
    }
}


class MonkeyPermissionEvent(private val mPkg: String, private val mPermissionInfo: PermissionInfo) :
    MonkeyEvent(EVENT_TYPE_PERMISSION) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        val pm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
        try {
            // determine if we should grant or revoke permission
            val perm = pm.checkPermission(mPermissionInfo.name, mPkg, UserHandle.myUserId())
            val grant = perm == PackageManager.PERMISSION_DENIED
            // log before calling pm in case we hit an error
            println(
                String.format(
                    ":Permission %s %s to package %s", if (grant) "grant" else "revoke",
                    mPermissionInfo.name, mPkg
                )
            )
            if (grant) {
                pm.grantRuntimePermission(mPkg, mPermissionInfo.name, UserHandle.myUserId())
            } else {
                pm.revokeRuntimePermission(mPkg, mPermissionInfo.name, UserHandle.myUserId())
            }
            return MonkeyEvent.INJECT_SUCCESS
        } catch (re: RemoteException) {
            return MonkeyEvent.INJECT_ERROR_REMOTE_EXCEPTION
        }

    }
}

/**
 * Special events for power measurement. Used to store test results and write to sd card. DO NO REAL MEASUREMENT!!
 */
class MonkeyPowerEvent : MonkeyEvent {

    private var mPowerLogTag: String? = null
    private var mTestResult: String? = null

    constructor(powerLogTag: String, powerTestResult: String) : super(EVENT_TYPE_ACTIVITY) {
        mPowerLogTag = powerLogTag
        mTestResult = powerTestResult
    }

    constructor(powerLogTag: String) : super(EVENT_TYPE_ACTIVITY) {
        mPowerLogTag = powerLogTag
        mTestResult = null
    }

    constructor() : super(EVENT_TYPE_ACTIVITY) {
        mPowerLogTag = null
        mTestResult = null
    }

    /**
     * Buffer an event to be logged later.
     */
    private fun bufferLogEvent(tag: String, value: String?) {
        var _tag = tag
        var tagTime = System.currentTimeMillis()
        // Record the test start time
        if (_tag.compareTo(TEST_STARTED) == 0) {
            mTestStartTime = tagTime
        } else if (_tag.compareTo(TEST_IDLE_ENDED) == 0) {
            val lagTime = java.lang.Long.parseLong(value)
            tagTime = mTestStartTime + lagTime
            _tag = TEST_ENDED
        } else if (_tag.compareTo(TEST_DELAY_STARTED) == 0) {
            mTestStartTime = tagTime + USB_DELAY_TIME
            tagTime = mTestStartTime
            _tag = TEST_STARTED
        }

        val event = ContentValues()
        event.put("date", tagTime)
        event.put("tag", _tag)

        if (value != null) {
            event.put("value", value)
        }
        mLogEvents.add(event)
    }

    /**
     * Write the accumulated log events to a file on the SD card.
     */
    private fun writeLogEvents() {
        val events = mLogEvents.toTypedArray()
        mLogEvents.clear()
        var writer: FileWriter? = null
        try {
            val buffer = StringBuffer()
            for (i in events.indices) {
                val event = events[i]
                buffer.append(MonkeyUtils.toCalendarTime(event.getAsLong("date")))
                buffer.append(event.getAsString("tag"))
                if (event.containsKey("value")) {
                    val value = event.getAsString("value")
                    buffer.append(" ")
                    buffer.append(value.replace('\n', '/'))
                }
                buffer.append("\n")
            }
            writer = FileWriter(LOG_FILE, true) // true = append
            writer.write(buffer.toString())
        } catch (e: IOException) {
            Log.w(TAG, "Can't write sdcard log file", e)
        } finally {
            try {
                writer?.close()
            } catch (e: IOException) {
            }

        }
    }

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        if (mPowerLogTag != null) {
            if (mPowerLogTag!!.compareTo(TEST_SEQ_BEGIN) == 0) {
                bufferLogEvent(mPowerLogTag!!, Build.FINGERPRINT)
            } else if (mTestResult != null) {
                bufferLogEvent(mPowerLogTag!!, mTestResult)
            }
        } else {
            writeLogEvents()
        }
        return MonkeyEvent.INJECT_SUCCESS
    }

    companion object {

        // Parameter for the power test runner
        private const val TAG = "PowerTester"
        private const val LOG_FILE = "/sdcard/autotester.log"
        private val mLogEvents = ArrayList<ContentValues>()
        private const val TEST_SEQ_BEGIN = "AUTOTEST_SEQUENCE_BEGIN"
        private const val TEST_STARTED = "AUTOTEST_TEST_BEGIN"
        private const val TEST_DELAY_STARTED = "AUTOTEST_TEST_BEGIN_DELAY"
        private const val TEST_ENDED = "AUTOTEST_TEST_SUCCESS"
        private const val TEST_IDLE_ENDED = "AUTOTEST_IDLE_SUCCESS"
        private var mTestStartTime: Long = 0

        // 10 secs for the screen to trun off after the usb notification
        private const val USB_DELAY_TIME: Long = 10000
    }
}

/**
 * monkey screen rotation event
 */
class MonkeyRotationEvent
/**
 * Construct a rotation Event.
 *
 * @param degree
 * Possible rotation degrees, see constants in
 * anroid.view.Suface.
 * @param persist
 * Should we keep the rotation lock after the orientation change.
 */
    (val mRotationDegree: Int, val mPersist: Boolean) : MonkeyEvent(EVENT_TYPE_ROTATION) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        if (verbose > 0) {
            Logger.lPrintln(":Sending rotation degree=$mRotationDegree, persist=$mPersist")
        }

        // inject rotation event
        try {
            //freezeRotation: rotate and not allow to re-rotate
            iwm.freezeRotation(mRotationDegree)
            if (!mPersist) {
                //thawRotation: allow to re-rotate
                iwm.thawRotation()
            }
            //Thread.sleep(1000)
            return MonkeyEvent.INJECT_SUCCESS
        } catch (ex: RemoteException) {
            return MonkeyEvent.INJECT_ERROR_REMOTE_EXCEPTION
        }

    }
}

/**
 * monkey throttle event, just sleep for given amount time
 */
class MonkeyThrottleEvent(/* private */ internal var mThrottle: Long) : MonkeyEvent(MonkeyEvent.EVENT_TYPE_THROTTLE) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {

        if (verbose > 1) {
            Logger.lPrintln("Sleeping for $mThrottle milliseconds")
        }
        try {
            Thread.sleep(mThrottle)
        } catch (e1: InterruptedException) {
            println("** Monkey interrupted in sleep.")
            return INJECT_FAIL
        }

        return INJECT_SUCCESS
    }
}

/**
 * monkey throttle event, completely the same as the last one.......
 */
class MonkeyWaitEvent(/* private */ internal var mWaitTime: Long) : MonkeyEvent(MonkeyEvent.EVENT_TYPE_THROTTLE) {

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        if (verbose > 1) {
            Logger.lPrintln("Wait Event for $mWaitTime milliseconds")
        }
        try {
            Thread.sleep(mWaitTime)
        } catch (e1: InterruptedException) {
            println("** Monkey interrupted in sleep.")
            return INJECT_FAIL
        }

        return INJECT_SUCCESS
    }
}

/**
 * monkey motion event, extended by the MonkeyTouchEvent and MonkeyTrackballEvent
 * */
//TODO: extract all private fields out of the constructor and override their getters/setters
public abstract class MonkeyMotionEvent protected constructor(type: Int, private val mSource : Int,
                                                                //the Type and mSource must be specified, as it defines what type the event is (e.g., EVENT_TYPE_TOUCH)
                                                                //and to which source (e.g, screen or trackball), others can be mocked
                                                               private val mAction: Int, private var mDownTime: Long = -1L,
                                                               private var mEventTime: Long = -1L, private var mXPrecision : Float = 1f,
                                                               private var mYPrecision : Float = 1f) : MonkeyEvent(type) {
    private val mPointers = SparseArray<MotionEvent.PointerCoords>()

    // If true, this is an intermediate step (more verbose logging, only)
    private var mIntermediateNote: Boolean = false

    private var mMetaState = 0
    private var mDeviceId: Int = 0
    private val mFlags: Int = 0
    private var mEdgeFlags: Int = 0

    fun addPointer(id: Int, x: Float, y: Float) = addPointer(id, x, y, 0F, 0F)
    open fun addPointer(id: Int, x: Float, y: Float, pressure: Float, size: Float)  = apply {
        val c = MotionEvent.PointerCoords()
        c.x = x
        c.y = y
        c.pressure = pressure
        c.size = size
        mPointers.append(id, c)
    }

    fun setIntermediateNote(b: Boolean) = this.apply { mIntermediateNote = b }
    fun getIntermediateNote() = mIntermediateNote
    fun getAction() = mAction
    fun getDownTime() = mDownTime
    fun getEventTime() = mEventTime

    fun setDownTime(downTime: Long) = this.apply { mDownTime = downTime }
    fun setEventTime(eventTime: Long) = this.apply { mEventTime = eventTime }
    fun setMetaState(metaState : Int) = this.apply { mMetaState = metaState }
    fun setPrecision(xPrecision: Float, yPrecision: Float) = this.apply {
        mXPrecision = xPrecision
        mYPrecision = yPrecision
    }
    fun setDeviceId(deviceId: Int) = this.apply { mDeviceId = deviceId }
    fun setEdgeFlags(edgeFlags: Int) = this.apply { mEdgeFlags = edgeFlags }


    /**
     *
     * @return instance of a motion event
     */
    internal fun getEvent(): MotionEvent {
        val pointerCount = mPointers.size()
        val pointerIds = IntArray(pointerCount){ mPointers.keyAt(it) }
        val pointerCoords = Array<MotionEvent.PointerCoords>(pointerCount){mPointers.valueAt(it)}

        return MotionEvent.obtain(mDownTime, if (mEventTime < 0) SystemClock.uptimeMillis() else mEventTime,
            mAction, pointerCount, pointerIds, pointerCoords, mMetaState, mXPrecision, mYPrecision, mDeviceId, mEdgeFlags, mSource, mFlags
        )
    }

    override fun isThrottlable() = mAction == MotionEvent.ACTION_UP

    override fun injectEvent(iwm: IWindowManager, iam: IActivityManager, verbose: Int): Int {
        //println("Inject Event! $mAction")
        val me = getEvent()
        //println("Inject Event! ${me.action}  ${me.actionMasked}")
        if ((verbose > 0 /*&& !mIntermediateNote*/) || verbose > 1) {
            val points = StringBuilder()
            for (i in 0 until me.pointerCount) {
                points.append(" ${me.getPointerId(i)}:(${me.getX(i)},${me.getY(i)})")
            }
            Logger.lPrintln(":Sending ${getTypeLabel()} (${
                when(me.actionMasked) {
                    MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
                    MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
                    MotionEvent.ACTION_UP   -> "ACTION_UP"
                    MotionEvent.ACTION_CANCEL -> "ACTION_CANCEL"
                    MotionEvent.ACTION_POINTER_DOWN -> "ACTION_POINTER_DOWN ${me.getPointerId(me.actionIndex)}"
                    MotionEvent.ACTION_POINTER_UP -> "ACTION_POINTER_UP ${me.getPointerId(me.actionIndex)}"
                    else -> mAction
                }
            }):$points")
        }
        try {
            if (!InputManager.getInstance().injectInputEvent(me,
                    InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)) {return INJECT_FAIL}
        } finally {
            me.recycle()
        }
        return INJECT_SUCCESS
    }

    protected abstract fun getTypeLabel() : String
}

/**
 * monkey touch event, note that the
 */
class MonkeyTouchEvent(action: Int) :
    MonkeyMotionEvent(EVENT_TYPE_TOUCH, InputDevice.SOURCE_TOUCHSCREEN, action) {

    override fun getTypeLabel() = "Touch"

    override fun addPointer(id: Int, x: Float, y: Float, pressure: Float, size: Float): MonkeyMotionEvent {
        var _y = y
        if (_y < statusBarHeight) { // avoid touch status bar
            _y = (statusBarHeight + 1).toFloat()
        }
        return super.addPointer(id, x, _y, pressure, size)
    }

    companion object {

        internal val statusBarHeight: Int

        //The status bar is 24 dp, and the statusBarHeight is its actual px
        init {
            val display = DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY)
            val dm = DisplayMetrics()
            display.getMetrics(dm)
            statusBarHeight = (24 * dm.density).toInt()
        }
    }
}

/**
 * monkey trackball event
 */
class MonkeyTrackballEvent(action: Int) :
    MonkeyMotionEvent(EVENT_TYPE_TRACKBALL, InputDevice.SOURCE_TRACKBALL, action) {

    override fun getTypeLabel() = "Trackball"
}


