package edu.nju.ics.alex.combodroid.monkey

import android.content.ComponentName
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.*
import kotlin.random.Random


/**
 * monkey event queue. It takes a script to produce events sample script format:
 *
 * <pre>
 * type= raw events
 * count= 10
 * speed= 1.0
 * start data &gt;&gt;
 * captureDispatchPointer(5109520,5109520,0,230.75429,458.1814,0.20784314,0.06666667,0,0.0,0.0,65539,0)
 * captureDispatchKey(5113146,5113146,0,20,0,0,0,0)
 * captureDispatchFlip(true)
 * ...
</pre> *
 */
class MonkeySourceScript
/**
 * Creates a MonkeySourceScript instance.
 *
 * @param filename
 * The filename of the script (on the device).
 * @param throttle
 * The amount of time in ms to sleep between events.
 */
    (
    random: Random, private val mScriptFileName: String, throttle: Long, randomizeThrottle: Boolean,
    profileWaitTime: Long, deviceSleepTime: Long
) : MonkeyEventSource {
    private var mEventCountInScript = 0 // total number of events in the file

    private var mVerbose = 0

    private var mSpeed = 1.0

    private val mQ: MonkeyEventQueue = MonkeyEventQueue(random, throttle, randomizeThrottle)

    private var mLastRecordedDownTimeKey: Long = 0

    private var mLastRecordedDownTimeMotion: Long = 0

    private var mLastExportDownTimeKey: Long = 0

    private var mLastExportDownTimeMotion: Long = 0

    private var mLastExportEventTime: Long = -1

    private var mLastRecordedEventTime: Long = -1

    // process scripts in line-by-line mode (true) or batch processing mode
    // (false)
    private var mReadScriptLineByLine = false

    private var mFileOpened = false

    private var mProfileWaitTime: Long = 5000 // Wait time for each user profile

    private var mDeviceSleepTime: Long = 30000 // Device sleep time

    internal var mFStream: FileInputStream = FileInputStream(mScriptFileName)

    internal var mInputStream: DataInputStream = DataInputStream(mFStream)

    internal var mBufferedReader: BufferedReader = BufferedReader(InputStreamReader(mInputStream))

    // X and Y coordincates of last touch event. Array Index is the pointerId
    private val mLastX = FloatArray(2)

    private val mLastY = FloatArray(2)

    private var mScriptStartTime: Long = -1

    private var mMonkeyStartTime: Long = -1




    init {
        mProfileWaitTime = profileWaitTime
        mDeviceSleepTime = deviceSleepTime
    }

    /**
     * Gets the next event to be injected from the script. If the event queue is
     * empty, reads the next n events from the script into the queue, where n is
     * the lesser of the number of remaining events and the value specified by
     * MAX_ONE_TIME_READS. If the end of the file is reached, no events are
     * added to the queue and null is returned.
     *
     * @return The first event in the event queue or null if the end of the file
     * is reached or if an error is encountered reading the file.
     */
    override fun getNextEvent(): MonkeyEvent? {
        var recordedEventTime = -1
        if (mQ.isEmpty()) {
            try {
                readNextBatch()
            } catch (e: IOException) {
                return null
            }
        }
        val ev : MonkeyEvent = try {
            val tem = mQ.first
            mQ.removeFirst()
            tem
        } catch (e: NoSuchElementException) {
            return null
        }
        if (ev.eventType == MonkeyEvent.EVENT_TYPE_KEY) {
            adjustKeyEventTime(ev as MonkeyKeyEvent)
        } else if (ev.eventType == MonkeyEvent.EVENT_TYPE_TOUCH || ev.eventType == MonkeyEvent.EVENT_TYPE_TRACKBALL) {
            adjustMotionEventTime(ev as MonkeyMotionEvent)
        }
        return ev
    }

    /**
     * Resets the globals used to timeshift events.
     */
    private fun resetValue() {
        mLastRecordedDownTimeKey = 0
        mLastRecordedDownTimeMotion = 0
        mLastRecordedEventTime = -1
        mLastExportDownTimeKey = 0
        mLastExportDownTimeMotion = 0
        mLastExportEventTime = -1
    }

    /**
     * Reads the header of the script file.
     *
     * @return True if the file header could be parsed, and false otherwise.
     * @throws IOException
     * If there was an error reading the file.
     */
    @Throws(IOException::class)
    private fun readHeader(): Boolean {
        mFileOpened = true

        mFStream = FileInputStream(mScriptFileName)
        mInputStream = DataInputStream(mFStream)
        mBufferedReader = BufferedReader(InputStreamReader(mInputStream))

        var line: String? = mBufferedReader.readLine()

        while (line != null) {
            line = line.trim { it <= ' ' }

            if (line.indexOf(HEADER_COUNT) >= 0) {
                try {
                    val value = line.substring(HEADER_COUNT.length + 1).trim { it <= ' ' }
                    mEventCountInScript = Integer.parseInt(value)
                } catch (e: NumberFormatException) {
                    System.err.println(e)
                    return false
                }

            } else if (line.indexOf(HEADER_SPEED) >= 0) {
                try {
                    val value = line.substring(HEADER_COUNT.length + 1).trim { it <= ' ' }
                    mSpeed = java.lang.Double.parseDouble(value)
                } catch (e: NumberFormatException) {
                    System.err.println(e)
                    return false
                }

            } else if (line.indexOf(HEADER_LINE_BY_LINE) >= 0) {
                mReadScriptLineByLine = true
            } else if (line.indexOf(STARTING_DATA_LINE) >= 0) {
                return true
            }

            line = mBufferedReader.readLine()
        }

        return false
    }

    /**
     * Reads a number of lines and passes the lines to be processed.
     *
     * @return The number of lines read.
     * @throws IOException
     * If there was an error reading the file.
     */
    @Throws(IOException::class)
    private fun readLines(): Int {
        var line: String?
        for (i in 0 until MAX_ONE_TIME_READS) {
            line = mBufferedReader.readLine()
            if (line == null) {
                return i
            }
            line.trim { it <= ' ' }
            processLine(line)
        }
        return MAX_ONE_TIME_READS
    }

    /**
     * Reads one line and processes it.
     *
     * @return the number of lines read
     * @throws IOException
     * If there was an error reading the file.
     */
    @Throws(IOException::class)
    private fun readOneLine(): Int {
        val line = mBufferedReader.readLine() ?: return 0
        line.trim { it <= ' ' }
        processLine(line)
        return 1
    }

    /**
     * Creates an event and adds it to the event queue. If the parameters are
     * not understood, they are ignored and no events are added.
     *
     * @param s
     * The entire string from the script file.
     * @param args
     * An array of arguments extracted from the script file line.
     */
    private fun handleEvent(s: String, args: Array<String>) {
        // Handle key event
        if (s.indexOf(EVENT_KEYWORD_KEY) >= 0 && args.size == 8) {
            try {
                println(" old key\n")
                val downTime = java.lang.Long.parseLong(args[0])
                val eventTime = java.lang.Long.parseLong(args[1])
                val action = Integer.parseInt(args[2])
                val code = Integer.parseInt(args[3])
                val repeat = Integer.parseInt(args[4])
                val metaState = Integer.parseInt(args[5])
                val device = Integer.parseInt(args[6])
                val scancode = Integer.parseInt(args[7])

                val e = MonkeyKeyEvent(
                    downTime, eventTime, action, code, repeat, metaState, device,
                    scancode
                )
                println(" Key code $code\n")

                mQ.addLast(e)
                println("Added key up \n")
            } catch (e: NumberFormatException) {
            }

            return
        }

        // Handle trackball or pointer events
        if ((s.indexOf(EVENT_KEYWORD_POINTER) >= 0 || s.indexOf(EVENT_KEYWORD_TRACKBALL) >= 0) && args.size == 12) {
            try {
                val downTime = java.lang.Long.parseLong(args[0])
                val eventTime = java.lang.Long.parseLong(args[1])
                val action = Integer.parseInt(args[2])
                val x = java.lang.Float.parseFloat(args[3])
                val y = java.lang.Float.parseFloat(args[4])
                val pressure = java.lang.Float.parseFloat(args[5])
                val size = java.lang.Float.parseFloat(args[6])
                val metaState = Integer.parseInt(args[7])
                val xPrecision = java.lang.Float.parseFloat(args[8])
                val yPrecision = java.lang.Float.parseFloat(args[9])
                val device = Integer.parseInt(args[10])
                val edgeFlags = Integer.parseInt(args[11])

                val e: MonkeyMotionEvent
                if (s.indexOf("Pointer") > 0) {
                    e = MonkeyTouchEvent(action)
                } else {
                    e = MonkeyTrackballEvent(action)
                }

                e.setDownTime(downTime).setEventTime(eventTime).setMetaState(metaState)
                    .setPrecision(xPrecision, yPrecision).setDeviceId(device).setEdgeFlags(edgeFlags)
                    .addPointer(0, x, y, pressure, size)
                mQ.addLast(e)
            } catch (e: NumberFormatException) {
            }

            return
        }

        // Handle trackball or multi-touch pointer events. pointer ID is the
        // 13th parameter
        if ((s.indexOf(EVENT_KEYWORD_POINTER) >= 0 || s.indexOf(EVENT_KEYWORD_TRACKBALL) >= 0) && args.size == 13) {
            try {
                val downTime = java.lang.Long.parseLong(args[0])
                val eventTime = java.lang.Long.parseLong(args[1])
                val action = Integer.parseInt(args[2])
                val x = java.lang.Float.parseFloat(args[3])
                val y = java.lang.Float.parseFloat(args[4])
                val pressure = java.lang.Float.parseFloat(args[5])
                val size = java.lang.Float.parseFloat(args[6])
                val metaState = Integer.parseInt(args[7])
                val xPrecision = java.lang.Float.parseFloat(args[8])
                val yPrecision = java.lang.Float.parseFloat(args[9])
                val device = Integer.parseInt(args[10])
                val edgeFlags = Integer.parseInt(args[11])
                val pointerId = Integer.parseInt(args[12])

                val e: MonkeyMotionEvent
                if (s.indexOf("Pointer") > 0) {
                    if (action == MotionEvent.ACTION_POINTER_DOWN) {
                        e = MonkeyTouchEvent(
                            MotionEvent.ACTION_POINTER_DOWN or (pointerId shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        )
                            .setIntermediateNote(true)
                    } else {
                        e = MonkeyTouchEvent(action)
                    }
                    if (mScriptStartTime < 0) {
                        mMonkeyStartTime = SystemClock.uptimeMillis()
                        mScriptStartTime = eventTime
                    }
                } else {
                    e = MonkeyTrackballEvent(action)
                }

                if (pointerId == 1) {
                    e.setDownTime(downTime).setEventTime(eventTime).setMetaState(metaState)
                        .setPrecision(xPrecision, yPrecision).setDeviceId(device).setEdgeFlags(edgeFlags)
                        .addPointer(0, mLastX[0], mLastY[0], pressure, size).addPointer(1, x, y, pressure, size)
                    mLastX[1] = x
                    mLastY[1] = y
                } else if (pointerId == 0) {
                    e.setDownTime(downTime).setEventTime(eventTime).setMetaState(metaState)
                        .setPrecision(xPrecision, yPrecision).setDeviceId(device).setEdgeFlags(edgeFlags)
                        .addPointer(0, x, y, pressure, size)
                    if (action == MotionEvent.ACTION_POINTER_UP) {
                        e.addPointer(1, mLastX[1], mLastY[1])
                    }
                    mLastX[0] = x
                    mLastY[0] = y
                }

                // Dynamically adjust waiting time to ensure that simulated
                // evnets follow
                // the time tap specified in the script
                if (mReadScriptLineByLine) {
                    val curUpTime = SystemClock.uptimeMillis()
                    val realElapsedTime = curUpTime - mMonkeyStartTime
                    val scriptElapsedTime = eventTime - mScriptStartTime
                    if (realElapsedTime < scriptElapsedTime) {
                        val waitDuration = scriptElapsedTime - realElapsedTime
                        mQ.addLast(MonkeyWaitEvent(waitDuration))
                    }
                }
                mQ.addLast(e)
            } catch (e: NumberFormatException) {
            }

            return
        }

        // Handle screen rotation events
        if (s.indexOf(EVENT_KEYWORD_ROTATION) >= 0 && args.size == 2) {
            try {
                val rotationDegree = Integer.parseInt(args[0])
                val persist = Integer.parseInt(args[1])
                if (rotationDegree == Surface.ROTATION_0 || rotationDegree == Surface.ROTATION_90
                    || rotationDegree == Surface.ROTATION_180 || rotationDegree == Surface.ROTATION_270
                ) {
                    mQ.addLast(MonkeyRotationEvent(rotationDegree, persist != 0))
                }
            } catch (e: NumberFormatException) {
            }

            return
        }

        // Handle tap event
        if (s.indexOf(EVENT_KEYWORD_TAP) >= 0 && args.size >= 2) {
            try {
                val x = java.lang.Float.parseFloat(args[0])
                val y = java.lang.Float.parseFloat(args[1])
                var tapDuration: Long = 0
                if (args.size == 3) {
                    tapDuration = java.lang.Long.parseLong(args[2])
                }

                // Set the default parameters
                val downTime = SystemClock.uptimeMillis()
                val e1 = MonkeyTouchEvent(MotionEvent.ACTION_DOWN).setDownTime(downTime)
                    .setEventTime(downTime).addPointer(0, x, y, 1F, 5F)
                mQ.addLast(e1)
                if (tapDuration > 0) {
                    mQ.addLast(MonkeyWaitEvent(tapDuration))
                }
                val e2 = MonkeyTouchEvent(MotionEvent.ACTION_UP).setDownTime(downTime)
                    .setEventTime(downTime).addPointer(0, x, y, 1F, 5F)
                mQ.addLast(e2)
            } catch (e: NumberFormatException) {
                System.err.println("// $e")
            }

            return
        }

        // Handle the press and hold
        if (s.indexOf(EVENT_KEYWORD_PRESSANDHOLD) >= 0 && args.size == 3) {
            try {
                val x = java.lang.Float.parseFloat(args[0])
                val y = java.lang.Float.parseFloat(args[1])
                val pressDuration = java.lang.Long.parseLong(args[2])

                // Set the default parameters
                val downTime = SystemClock.uptimeMillis()

                val e1 = MonkeyTouchEvent(MotionEvent.ACTION_DOWN).setDownTime(downTime)
                    .setEventTime(downTime).addPointer(0, x, y, 1F, 5F)
                val e2 = MonkeyWaitEvent(pressDuration)
                val e3 = MonkeyTouchEvent(MotionEvent.ACTION_UP).setDownTime(downTime + pressDuration)
                    .setEventTime(downTime + pressDuration).addPointer(0, x, y, 1F, 5F)
                mQ.addLast(e1)
                mQ.addLast(e2)
                // originally e2... a mistake?
                mQ.addLast(e3)

            } catch (e: NumberFormatException) {
                System.err.println("// $e")
            }

            return
        }

        // Handle drag event
        if (s.indexOf(EVENT_KEYWORD_DRAG) >= 0 && args.size == 5) {
            val xStart = java.lang.Float.parseFloat(args[0])
            val yStart = java.lang.Float.parseFloat(args[1])
            val xEnd = java.lang.Float.parseFloat(args[2])
            val yEnd = java.lang.Float.parseFloat(args[3])
            val stepCount = Integer.parseInt(args[4])

            var x = xStart
            var y = yStart
            val downTime = SystemClock.uptimeMillis()
            var eventTime = SystemClock.uptimeMillis()

            if (stepCount > 0) {
                val xStep = (xEnd - xStart) / stepCount
                val yStep = (yEnd - yStart) / stepCount

                var e = MonkeyTouchEvent(MotionEvent.ACTION_DOWN).setDownTime(downTime)
                    .setEventTime(eventTime).addPointer(0, x, y, 1F, 5F)
                mQ.addLast(e)

                for (i in 0 until stepCount) {
                    x += xStep
                    y += yStep
                    eventTime = SystemClock.uptimeMillis()
                    e = MonkeyTouchEvent(MotionEvent.ACTION_MOVE).setDownTime(downTime).setEventTime(eventTime)
                        .addPointer(0, x, y, 1F, 5F)
                    mQ.addLast(e)
                }

                eventTime = SystemClock.uptimeMillis()
                e = MonkeyTouchEvent(MotionEvent.ACTION_UP).setDownTime(downTime).setEventTime(eventTime)
                    .addPointer(0, x, y, 1F, 5F)
                mQ.addLast(e)
            }
        }

        // Handle pinch or zoom action
        if (s.indexOf(EVENT_KEYWORD_PINCH_ZOOM) >= 0 && args.size == 9) {
            // Parse the parameters
            val pt1xStart = java.lang.Float.parseFloat(args[0])
            val pt1yStart = java.lang.Float.parseFloat(args[1])
            val pt1xEnd = java.lang.Float.parseFloat(args[2])
            val pt1yEnd = java.lang.Float.parseFloat(args[3])

            val pt2xStart = java.lang.Float.parseFloat(args[4])
            val pt2yStart = java.lang.Float.parseFloat(args[5])
            val pt2xEnd = java.lang.Float.parseFloat(args[6])
            val pt2yEnd = java.lang.Float.parseFloat(args[7])

            val stepCount = Integer.parseInt(args[8])

            var x1 = pt1xStart
            var y1 = pt1yStart
            var x2 = pt2xStart
            var y2 = pt2yStart

            val downTime = SystemClock.uptimeMillis()
            var eventTime = SystemClock.uptimeMillis()

            if (stepCount > 0) {
                val pt1xStep = (pt1xEnd - pt1xStart) / stepCount
                val pt1yStep = (pt1yEnd - pt1yStart) / stepCount

                val pt2xStep = (pt2xEnd - pt2xStart) / stepCount
                val pt2yStep = (pt2yEnd - pt2yStart) / stepCount

                mQ.addLast(
                    MonkeyTouchEvent(MotionEvent.ACTION_DOWN).setDownTime(downTime).setEventTime(eventTime)
                        .addPointer(0, x1, y1, 1F, 5F)
                )

                mQ.addLast(
                    MonkeyTouchEvent(
                        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    )
                        .setDownTime(downTime).addPointer(0, x1, y1).addPointer(1, x2, y2)
                        .setIntermediateNote(true)
                )

                for (i in 0 until stepCount) {
                    x1 += pt1xStep
                    y1 += pt1yStep
                    x2 += pt2xStep
                    y2 += pt2yStep

                    eventTime = SystemClock.uptimeMillis()
                    mQ.addLast(
                        MonkeyTouchEvent(MotionEvent.ACTION_MOVE).setDownTime(downTime)
                            .setEventTime(eventTime).addPointer(0, x1, y1, 1F, 5F).addPointer(1, x2, y2, 1F, 5F)
                    )
                }
                eventTime = SystemClock.uptimeMillis()
                mQ.addLast(
                    MonkeyTouchEvent(MotionEvent.ACTION_POINTER_UP).setDownTime(downTime)
                        .setEventTime(eventTime).addPointer(0, x1, y1).addPointer(1, x2, y2)
                )
            }
        }

        // Handle flip events
        if (s.indexOf(EVENT_KEYWORD_FLIP) >= 0 && args.size == 1) {
            val keyboardOpen = java.lang.Boolean.parseBoolean(args[0])
            val e = MonkeyFlipEvent(keyboardOpen)
            mQ.addLast(e)
        }

        // Handle launch events
        if (s.indexOf(EVENT_KEYWORD_ACTIVITY) >= 0 && args.size >= 2) {
            val pkg_name = args[0]
            val cl_name = args[1]
            var alarmTime: Long = 0

            val mApp = ComponentName(pkg_name, cl_name)

            if (args.size > 2) {
                try {
                    alarmTime = java.lang.Long.parseLong(args[2])
                } catch (e: NumberFormatException) {
                    System.err.println("// $e")
                    return
                }

            }

            if (args.size == 2) {
                val e = MonkeyActivityEvent(mApp)
                mQ.addLast(e)
            } else {
                val e = MonkeyActivityEvent(mApp, alarmTime)
                mQ.addLast(e)
            }
            return
        }

        // Handle the device wake up event
        if (s.indexOf(EVENT_KEYWORD_DEVICE_WAKEUP) >= 0) {
            val pkg_name = "com.google.android.powerutil"
            val cl_name = "com.google.android.powerutil.WakeUpScreen"
            val deviceSleepTime = mDeviceSleepTime

            // Start the wakeUpScreen test activity to turn off the screen.
            val mApp = ComponentName(pkg_name, cl_name)
            mQ.addLast(MonkeyActivityEvent(mApp, deviceSleepTime))

            // inject the special key for the wakeUpScreen test activity.
            mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_DOWN, mKeyCode = KeyEvent.KEYCODE_0))
            mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_UP, mKeyCode = KeyEvent.KEYCODE_0))

            // Add the wait event after the device sleep event so that the
            // monkey
            // can continue after the device wake up.
            mQ.addLast(MonkeyWaitEvent(deviceSleepTime + 3000))

            // Insert the menu key to unlock the screen
            mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_DOWN, mKeyCode = KeyEvent.KEYCODE_MENU))
            mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_UP, mKeyCode = KeyEvent.KEYCODE_MENU))

            // Insert the back key to dismiss the test activity
            mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_DOWN, mKeyCode = KeyEvent.KEYCODE_BACK))
            mQ.addLast(MonkeyKeyEvent(mAction = KeyEvent.ACTION_UP, mKeyCode = KeyEvent.KEYCODE_BACK))

            return
        }

        // Handle launch instrumentation events
        if (s.indexOf(EVENT_KEYWORD_INSTRUMENTATION) >= 0 && args.size == 2) {
            val test_name = args[0]
            val runner_name = args[1]
            val e = MonkeyInstrumentationEvent(test_name, runner_name)
            mQ.addLast(e)
            return
        }

        // Handle wait events
        if (s.indexOf(EVENT_KEYWORD_WAIT) >= 0 && args.size == 1) {
            try {
                val sleeptime = Integer.parseInt(args[0]).toLong()
                val e = MonkeyWaitEvent(sleeptime)
                mQ.addLast(e)
            } catch (e: NumberFormatException) {
            }

            return
        }

        // Handle the profile wait time
        if (s.indexOf(EVENT_KEYWORD_PROFILE_WAIT) >= 0) {
            val e = MonkeyWaitEvent(mProfileWaitTime)
            mQ.addLast(e)
            return
        }

        // Handle keypress events
        if (s.indexOf(EVENT_KEYWORD_KEYPRESS) >= 0 && args.size == 1) {
            val key_name = args[0]
            val keyCode = MonkeySourceRandom.getKeyCode(key_name)
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                return
            }
            var e = MonkeyKeyEvent(mAction = KeyEvent.ACTION_DOWN, mKeyCode = keyCode)
            mQ.addLast(e)
            e = MonkeyKeyEvent(mAction = KeyEvent.ACTION_UP, mKeyCode = keyCode)
            mQ.addLast(e)
            return
        }

        // Handle longpress events
        if (s.indexOf(EVENT_KEYWORD_LONGPRESS) >= 0) {
            var e = MonkeyKeyEvent(mAction = KeyEvent.ACTION_DOWN, mKeyCode = KeyEvent.KEYCODE_DPAD_CENTER)
            mQ.addLast(e)
            val we = MonkeyWaitEvent(LONGPRESS_WAIT_TIME.toLong())
            mQ.addLast(we)
            e = MonkeyKeyEvent(mAction = KeyEvent.ACTION_UP, mKeyCode = KeyEvent.KEYCODE_DPAD_CENTER)
            mQ.addLast(e)
        }

        // The power log event is mainly for the automated power framework
        if (s.indexOf(EVENT_KEYWORD_POWERLOG) >= 0 && args.size > 0) {
            val power_log_type = args[0]
            val test_case_status: String

            if (args.size == 1) {
                val e = MonkeyPowerEvent(power_log_type)
                mQ.addLast(e)
            } else if (args.size == 2) {
                test_case_status = args[1]
                val e = MonkeyPowerEvent(power_log_type, test_case_status)
                mQ.addLast(e)
            }
        }

        // Write power log to sdcard
        if (s.indexOf(EVENT_KEYWORD_WRITEPOWERLOG) >= 0) {
            val e = MonkeyPowerEvent()
            mQ.addLast(e)
        }

        // Run the shell command
        if (s.indexOf(EVENT_KEYWORD_RUNCMD) >= 0 && args.size == 1) {
            val cmd = args[0]
            val e = MonkeyCommandEvent(cmd)
            mQ.addLast(e)
        }

        // Input the string through the shell command
        if (s.indexOf(EVENT_KEYWORD_INPUT_STRING) >= 0 && args.size == 1) {
            val input = args[0]
            val cmd = "input text $input"
            val e = MonkeyCommandEvent(cmd)
            mQ.addLast(e)
            return
        }

        if (s.indexOf(EVENT_KEYWORD_START_FRAMERATE_CAPTURE) >= 0) {
            val e = MonkeyGetFrameRateEvent("start")
            mQ.addLast(e)
            return
        }

        if (s.indexOf(EVENT_KEYWORD_END_FRAMERATE_CAPTURE) >= 0 && args.size == 1) {
            val input = args[0]
            val e = MonkeyGetFrameRateEvent("end", input)
            mQ.addLast(e)
            return
        }

        if (s.indexOf(EVENT_KEYWORD_START_APP_FRAMERATE_CAPTURE) >= 0 && args.size == 1) {
            val app = args[0]
            val e = MonkeyGetAppFrameRateEvent("start", app)
            mQ.addLast(e)
            return
        }

        if (s.indexOf(EVENT_KEYWORD_END_APP_FRAMERATE_CAPTURE) >= 0 && args.size == 2) {
            val app = args[0]
            val label = args[1]
            val e = MonkeyGetAppFrameRateEvent("end", app, label)
            mQ.addLast(e)
            return
        }

    }

    /**
     * Extracts an event and a list of arguments from a line. If the line does
     * not match the format required, it is ignored.
     *
     * @param line
     * A string in the form `cmd(arg1,arg2,arg3)`.
     */
    private fun processLine(line: String) {
        val index1 = line.indexOf('(')
        val index2 = line.indexOf(')')

        if (index1 < 0 || index2 < 0) {
            return
        }

        val args = line.substring(index1 + 1, index2).split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        for (i in args.indices) {
            args[i] = args[i].trim { it <= ' ' }
        }

        handleEvent(line, args)
    }

    /**
     * Closes the script file.
     *
     * @throws IOException
     * If there was an error closing the file.
     */
    @Throws(IOException::class)
    private fun closeFile() {
        mFileOpened = false

        try {
            mFStream.close()
            mInputStream.close()
        } catch (e: NullPointerException) {
            // File was never opened so it can't be closed.
        }

    }

    /**
     * Read next batch of events from the script file into the event queue.
     * Checks if the script is open and then reads the next MAX_ONE_TIME_READS
     * events or reads until the end of the file. If no events are read, then
     * the script is closed.
     *
     * @throws IOException
     * If there was an error reading the file.
     */
    @Throws(IOException::class)
    private fun readNextBatch() {
        var linesRead = 0

        if (THIS_DEBUG) {
            println("readNextBatch(): reading next batch of events")
        }

        if (!mFileOpened) {
            resetValue()
            readHeader()
        }

        linesRead = if (mReadScriptLineByLine) {
            readOneLine()
        } else {
            readLines()
        }

        if (linesRead == 0) {
            closeFile()
        }
    }

    /**
     * Sleep for a period of given time. Used to introduce latency between
     * events.
     *
     * @param time
     * The amount of time to sleep in ms
     */
    private fun needSleep(time: Long) {
        if (time < 1) {
            return
        }
        try {
            Thread.sleep(time)
        } catch (e: InterruptedException) {
        }

    }

    /**
     * Checks if the file can be opened and if the header is valid.
     *
     * @return True if the file exists and the header is valid, false otherwise.
     */
    override fun validate(): Boolean {
        val validHeader: Boolean
        try {
            validHeader = readHeader()
            closeFile()
        } catch (e: IOException) {
            return false
        }

        if (mVerbose > 0) {
            println("Replaying $mEventCountInScript events with speed $mSpeed")
        }
        return validHeader
    }

    override fun setVerbose(verbose: Int) {
        mVerbose = verbose
    }

    /**
     * Adjust key downtime and eventtime according to both recorded values and
     * current system time.
     *
     * @param e
     * A KeyEvent
     */
    private fun adjustKeyEventTime(e: MonkeyKeyEvent) {
        if (e.getEventTime() < 0) {
            return
        }
        val thisDownTime: Long
        val thisEventTime: Long
        val expectedDelay: Long

        if (mLastRecordedEventTime <= 0) {
            // first time event
            thisDownTime = SystemClock.uptimeMillis()
            thisEventTime = thisDownTime
        } else {
            if (e.getDownTime() != mLastRecordedDownTimeKey) {
                thisDownTime = e.getDownTime()
            } else {
                thisDownTime = mLastExportDownTimeKey
            }
            expectedDelay = ((e.getEventTime() - mLastRecordedEventTime) * mSpeed).toLong()
            thisEventTime = mLastExportEventTime + expectedDelay
            // add sleep to simulate everything in recording
            needSleep(expectedDelay - SLEEP_COMPENSATE_DIFF)
        }
        mLastRecordedDownTimeKey = e.getDownTime()
        mLastRecordedEventTime = e.getEventTime()
        e.setDownTime(thisDownTime)
        e.setEventTime(thisEventTime)
        mLastExportDownTimeKey = thisDownTime
        mLastExportEventTime = thisEventTime
    }

    /**
     * Adjust motion downtime and eventtime according to current system time.
     *
     * @param e
     * A MotionEvent
     */
    private fun adjustMotionEventTime(e: MonkeyMotionEvent) {
        val thisEventTime = SystemClock.uptimeMillis()
        val thisDownTime = e.getDownTime()

        if (thisDownTime == mLastRecordedDownTimeMotion) {
            // this event is the same batch as previous one
            e.setDownTime(mLastExportDownTimeMotion)
        } else {
            // this event is the start of a new batch
            mLastRecordedDownTimeMotion = thisDownTime
            // update down time to match current time
            e.setDownTime(thisEventTime)
            mLastExportDownTimeMotion = thisEventTime
        }
        // always refresh event time
        e.setEventTime(thisEventTime)
    }

    companion object {

        private const val HEADER_COUNT = "count="

        private const val HEADER_SPEED = "speed="

        private const val THIS_DEBUG = false

        // a parameter that compensates the difference of real elapsed time and
        // time in theory
        private const val SLEEP_COMPENSATE_DIFF: Long = 16

        // if this header is present, scripts are read and processed in line-by-line
        // mode
        private const val HEADER_LINE_BY_LINE = "linebyline"

        // maximum number of events that we read at one time
        private const val MAX_ONE_TIME_READS = 100

        // event key word in the capture log
        private const val EVENT_KEYWORD_POINTER = "DispatchPointer"

        private const val EVENT_KEYWORD_TRACKBALL = "DispatchTrackball"

        private const val EVENT_KEYWORD_ROTATION = "RotateScreen"

        private const val EVENT_KEYWORD_KEY = "DispatchKey"

        private const val EVENT_KEYWORD_FLIP = "DispatchFlip"

        private const val EVENT_KEYWORD_KEYPRESS = "DispatchPress"

        private const val EVENT_KEYWORD_ACTIVITY = "LaunchActivity"

        private const val EVENT_KEYWORD_INSTRUMENTATION = "LaunchInstrumentation"

        private const val EVENT_KEYWORD_WAIT = "UserWait"

        private const val EVENT_KEYWORD_LONGPRESS = "LongPress"

        private const val EVENT_KEYWORD_POWERLOG = "PowerLog"

        private const val EVENT_KEYWORD_WRITEPOWERLOG = "WriteLog"

        private const val EVENT_KEYWORD_RUNCMD = "RunCmd"

        private const val EVENT_KEYWORD_TAP = "Tap"

        private const val EVENT_KEYWORD_PROFILE_WAIT = "ProfileWait"

        private const val EVENT_KEYWORD_DEVICE_WAKEUP = "DeviceWakeUp"

        private const val EVENT_KEYWORD_INPUT_STRING = "DispatchString"

        private const val EVENT_KEYWORD_PRESSANDHOLD = "PressAndHold"

        private const val EVENT_KEYWORD_DRAG = "Drag"

        private const val EVENT_KEYWORD_PINCH_ZOOM = "PinchZoom"

        private const val EVENT_KEYWORD_START_FRAMERATE_CAPTURE = "StartCaptureFramerate"

        private const val EVENT_KEYWORD_END_FRAMERATE_CAPTURE = "EndCaptureFramerate"

        private const val EVENT_KEYWORD_START_APP_FRAMERATE_CAPTURE = "StartCaptureAppFramerate"

        private const val EVENT_KEYWORD_END_APP_FRAMERATE_CAPTURE = "EndCaptureAppFramerate"

        // a line at the end of the header
        private const val STARTING_DATA_LINE = "start data >>"

        private const val LONGPRESS_WAIT_TIME = 2000 // wait time for the long
    }
}
