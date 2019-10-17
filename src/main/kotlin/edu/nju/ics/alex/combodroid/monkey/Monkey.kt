package edu.nju.ics.alex.combodroid.monkey

import android.app.ActivityManagerNative
import android.app.IActivityController
import android.app.IActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.IPackageManager
import android.os.*
import android.view.IWindowManager
import android.view.Surface
import edu.nju.ics.alex.combodroid.utils.Logger
import java.io.*
import java.lang.Exception
import java.lang.RuntimeException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.system.exitProcess


/**
 * Command-line entry point for Monkey (may be invoked by other components of ComboDroid)
 *
 * @param args
 *              The command-line arguments
 * */
fun main(args: Array<String?>) {
    // Set the process name  showing in the "ps" or "top"
    Process.setArgV0("com.android.commands.monkey")

    try{
        exitProcess(Monkey().run(args))
    } catch (e: Throwable) {
        Logger.lPrintln("Internal error")
        e.printStackTrace()
        Logger.lPrintln("Please report this bug to developers.")
        exitProcess(1)
    }
}


/**
 * Main class for modified Monkey, with two functionalities
 * (1) injecting random key events and other actions into the system, logging GUI transitions and API invocations,
 * (2) replaying a given trace
 * */

class Monkey {

    /**
     * Monkey Debugging/Dev Support
     *
     *
     * All values should be zero when checking in.
     */
    private val DEBUG_ALLOW_ANY_STARTS = 0

    private val DEBUG_ALLOW_ANY_RESTARTS = 0

    private var mAm: IActivityManager? = null

    private var mWm: IWindowManager? = null

    private var mPm: IPackageManager? = null

    /** Command line arguments  */
    private var mArgs: Array<String?>? = null

    /** Current argument being parsed  */
    private var mNextArg: Int = 0

    /** Data of current argument  */
    private var mCurArgData: String? = null

    /** Running in verbose output mode? 1= verbose, 2=very verbose  */
    private var mVerbose: Int = 0

    /** Ignore any application crashes while running?  */
    private var mIgnoreCrashes: Boolean = false

    /** Ignore any not responding timeouts while running?  */
    private var mIgnoreTimeouts: Boolean = false

    /** Ignore security exceptions when launching activities */
    /** (The activity launch still fails, but we keep pluggin' away)  */
    private var mIgnoreSecurityExceptions: Boolean = false

    /** Monitor /data/tombstones and stop the monkey if new files appear.  */
    private var mMonitorNativeCrashes: Boolean = false

    /** Ignore any native crashes while running?  */
    private var mIgnoreNativeCrashes: Boolean = false

    /** Send no events. Use with long throttle-time to watch user operations  */
    private var mSendNoEvents: Boolean = false

    /** This is set when we would like to abort the running of the monkey.  */
    private var mAbort: Boolean = false

    /**
     * Count each event as a cycle. Set to false for scripts so that each time
     * through the script increments the count.
     */
    private var mCountEvents = true

    /**
     * This is set by the ActivityController thread to request collection of ANR
     * trace files
     */
    private var mRequestAnrTraces = false

    /**
     * This is set by the ActivityController thread to request a "dumpsys
     * meminfo"
     */
    private var mRequestDumpsysMemInfo = false

    /**
     * This is set by the ActivityController thread to request a bug report after
     * ANR
     */
    private var mRequestAnrBugreport = false

    /**
     * This is set by the ActivityController thread to request a bug report after
     * a system watchdog report
     */
    private var mRequestWatchdogBugreport = false

    /**
     * Synchronization for the ActivityController callback to block until we are
     * done handling the reporting of the watchdog error.
     */
    private var mWatchdogWaiting = false

    /**
     * This is set by the ActivityController thread to request a bug report after
     * java application crash
     */
    private var mRequestAppCrashBugreport = false

    /** Request the bug report based on the mBugreportFrequency.  */
    private var mGetPeriodicBugreport = false

    /**
     * Request the bug report based on the mBugreportFrequency.
     */
    private var mRequestPeriodicBugreport = false

    /** Bug report frequency.  */
    private var mBugreportFrequency: Long = 10

    /** Failure process name  */
    private var mReportProcessName: String? = null

    /**
     * This is set by the ActivityController thread to request a "procrank"
     */
    private var mRequestProcRank = false

    /** Kill the process after a timeout or crash.  */
    private var mKillProcessAfterError: Boolean = false

    /** Generate hprof reports before/after monkey runs  */
    private var mGenerateHprof: Boolean = false

    /** Package blacklist file.  */
    private var mPkgBlacklistFile: String? = null

    /** Package whitelist file.  */
    private var mPkgWhitelistFile: String? = null

    /** Categories we are allowed to launch  */
    private val mMainCategories = ArrayList<String?>()

    /** Applications we can switch to.  */
    private val mMainApps = ArrayList<ComponentName>()

    /** The delay between event inputs  */
    internal var mThrottle: Long = 0

    /**
     * Whether to randomize each throttle (0-mThrottle ms) inserted between
     * events.
     */
    internal var mRandomizeThrottle = false

    /** The number of iterations  */
    internal var mCount = 1000

    /** The random number seed  */
    internal var mSeed: Long = 0

    /** The random number generator  */
    internal var mRandom: Random? = null

    /** Dropped-event statistics  */
    internal var mDroppedKeyEvents: Long = 0

    internal var mDroppedPointerEvents: Long = 0

    internal var mDroppedTrackballEvents: Long = 0

    internal var mDroppedFlipEvents: Long = 0

    internal var mDroppedRotationEvents: Long = 0

    /** The delay between user actions. This is for the scripted monkey.  */
    internal var mProfileWaitTime: Long = 5000

    /** Device idle time. This is for the scripted monkey.  */
    internal var mDeviceSleepTime: Long = 30000

    internal var mRandomizeScript = false

    internal var mScriptLog = false

    /** Capture bug reprot whenever there is a crash.  */
    private var mRequestBugreport = false

    /** a filename to the setup script (if any)  */
    private var mSetupFileName: String? = null

    /** filenames of the script (if any)  */
    private val mScriptFileNames = ArrayList<String?>()

    /** a TCP port to listen on for remote commands.  */
    private var mServerPort = -1

    private val TOMBSTONES_PATH = File("/data/tombstones")

    private var mTombstones: HashSet<String>? = null

    internal var mFactors = FloatArray(MonkeySourceRandom.FACTORZ_COUNT)

    /** used by Ape, directly copy for now*/
    private var mUseApe: Boolean = false
    private var mApeCleanApp: Boolean = false
    private var mApeAgent: String? = null
    private var mOutputDirectory: File? = null
    private val mUids = HashSet<Int>()
    private var mRunningMillis: Long = -1
    private var mEndTime: Long = 0

    internal var ONE_MINUTE_IN_MILLISECOND = (1000 * 60).toLong()

    internal lateinit var mEventSource: MonkeyEventSource

    private val mNetworkMonitor = MonkeyNetworkMonitor()

    private var mPermissionTargetSystem = false

    // information on the current activity. Initialized as stubs
    var currentIntent: Intent = Intent()


    /** used by ComboDroid */
    companion object {
        private val lock = ReentrantLock()
        private val condition = lock.newCondition()
        var currentPackage: String = ""
    }

    /**
     * Run the command!
     *
     * @param args
     *              The command-line arguments
     * @return Returns a posix-style result code. 0 for no error.
     * */
    fun run(args: Array<String?>): Int {
        //super-early debugger wait
        if (args.any { it == "--wait-dbg" }) {
            Debug.waitForDebugger()
        }

        //Default values for some command-line options
        mVerbose = 0
        mCount = 1000
        mSeed = 0
        mThrottle = 0

        mRunningMillis = -1

        //prepare for command-line processing
        mArgs = args
        mNextArg = 0

        //set a positive value, indicating none of the factors is provided yet
        /** modified here*/
        mFactors.fill(1.0f)

        //process options and allowed packages
        if (!processOptions()) return -1
        if (!loadPackageLists()) return -1

        //now set up additional data in preparation for launch
        if (mMainCategories.size == 0) {
            mMainCategories.add(Intent.CATEGORY_LAUNCHER)
            mMainCategories.add(Intent.CATEGORY_MONKEY)
        }

        if (mSeed == 0L) {
            mSeed = System.currentTimeMillis() + System.identityHashCode(this)
        }

        if (mVerbose > 0) {
            Logger.lPrintln(":Monkey: seed=$mSeed count=$mCount")
            MonkeyUtils.sFilter.dump()
            mMainCategories.forEach {
                Logger.lPrintln(":IncludeCategory: $it")
            }
        }

        if (!checkInternalConfiguration()) return -2
        if (!getSystemInterfaces()) return -3
        if (!getMainApps()) return -4

        mRandom = Random(mSeed)

        // a big when to determine where the events come from (script(s), socket, Ape, or random by default)
        when{
            mScriptFileNames.size == 1 -> {
                //script mode, ignore other options
                mEventSource = MonkeySourceScript(mRandom!!, mScriptFileNames[0]!!, mThrottle,
                    mRandomizeThrottle, mProfileWaitTime, mDeviceSleepTime)
                mEventSource.setVerbose(mVerbose)
                mCountEvents = false
            }

            mScriptFileNames.size > 1 -> {
                mEventSource = if (mSetupFileName != null) {
                        mCount++
                        MonkeySourceRandomScript(mSetupFileName, mScriptFileNames, mThrottle,
                        mRandomizeThrottle, mRandom!!, mProfileWaitTime, mDeviceSleepTime, mRandomizeScript)
                    } else {
                        MonkeySourceRandomScript(mScriptFileNames, mThrottle, mRandomizeScript, mRandom!!,
                        mProfileWaitTime, mDeviceSleepTime, mRandomizeScript)
                    }
                mEventSource.setVerbose(mVerbose)
                mCountEvents = false
            }

            //TODO: implement network-source Monkey
            mServerPort != -1 -> {
                throw Exception("not implemented yet!")
//                try {
//                    mEventSource = MonkeySourceNetwork(mServerPort)
//                } catch (e: IOException) {
//                    Logger.lPrintln("Error binding to network socket.")
//                    return -5
//                }
                //mCount = Int.MAX_VALUE
            }

            mUseApe -> {
                //the magic of Ape
                /*AndroidDevice: defined by ape*/
                //TODO: re-apply ape
               // AndroidDevice.initializeAndroidDevice(mAm!!, mWm!!, mPm!!)
               // AndroidDevice.checkInteractive()
               // mEventSource = MonkeySourceApe(mRandom!!, mMainApps, mThrottle,
               //     mRandomizeThrottle, mPermissionTargetSystem, mOutputDirectory!!)
//                mEventSource.setVerbose(mVerbose)
//                if (mApeCleanApp) {
//                    for (cn in mMainApps) {
//                        val packageName = cn.packageName
//                        AndroidDevice.stopPackage(packageName)
//                        val permissions = AndroidDevice.getGrantedPermissions(packageName)
//                        AndroidDevice.clearPackage(packageName, permissions!!)
//                    }
//                }
            }

            else -> {
                // random source by default

                //check seeding performance
                if (mVerbose >= 2) { Logger.lPrintln("// Seeded: $mSeed") }

                mEventSource = MonkeySourceRandom(mRandom!!, mMainApps, mThrottle, mRandomizeThrottle,
                    mPermissionTargetSystem)
                mEventSource.setVerbose(mVerbose)

                //set any of the factors that has been set
                mFactors.forEachIndexed { index, fl ->
                    if (fl <= 0f) {
                        (mEventSource as MonkeySourceRandom).setFactors(index, fl)
                    }
                }

                //in random mode, we start with a random activity
                (mEventSource as MonkeySourceRandom).generateActivity()
            }
        }

        // validate source generator
        if (!mEventSource.validate()) { return -5 }

        // if we are profiling, do it immediately before/after the main loop
        if (mGenerateHprof) { signalPersistentProcesses() }

        mNetworkMonitor.start()
        var crashedAtCycle : Int
        try {
            /** where the magic happens*/
            crashedAtCycle = runMonkeyCycles()
        } finally {
            //Release the rotation lock if it's still held and restore the original orientation
            MonkeyRotationEvent(Surface.ROTATION_0, false).injectEvent(mWm!!, mAm!!, mVerbose)
        }

        mNetworkMonitor.stop()

        //TODD: re-apply Monkey
//        if (mEventSource is MonkeySourceApe) {
//             //additional post process for Ape
//            (mEventSource as MonkeySourceApe).tearDown()
//        }

        // Kotlin does not have synchronized, notify, wait and notifyall.
        // use reentrantLock to achieve the goal
        lock.withLock {
            if (mRequestAnrTraces) {
                reportAnrTraces()
                mRequestAnrTraces = false
            }
            if (mRequestAnrBugreport) {
                Logger.lPrintln("Print the anr report")
                getBugreport("anr_" + mReportProcessName + "_")
                mRequestAnrBugreport = false
            }
            if (mRequestWatchdogBugreport) {
                Logger.lPrintln("Print the watchdog report")
                getBugreport("anr_watchdog_")
                mRequestWatchdogBugreport = false
            }
            if (mRequestAppCrashBugreport) {
                getBugreport("app_crash" + mReportProcessName + "_")
                mRequestAppCrashBugreport = false
            }
            if (mRequestDumpsysMemInfo) {
                reportDumpsysMemInfo()
                mRequestDumpsysMemInfo = false
            }
            if (mRequestPeriodicBugreport) {
                getBugreport("Bugreport_")
                mRequestPeriodicBugreport = false
            }
            if (mWatchdogWaiting) {
                mWatchdogWaiting = false
                condition.signalAll()
            }
        }

        if (mGenerateHprof) {
            signalPersistentProcesses()
            if (mVerbose > 0) {
                Logger.lPrintln("// Generated profiling reports in /data/misc")
            }
        }

        //additional clean up for Ape
        //TODO: re-apply ape
        try {
            //ApeAPIAdapter.setActivityController(mAm, null)
            mNetworkMonitor.unregister(mAm!!)
        } catch (e: RemoteException) {
            //just in case this was latent (after mCount cycles), make sure we report it
            if (crashedAtCycle >= mCount) { crashedAtCycle = -1}
        }

        //report dropped event stats
        if (mVerbose > 0) {
            Logger.lPrintln(":Dropped: Keys=$mDroppedKeyEvents " +
                    "pointers=$mDroppedPointerEvents " +
                    "trackballs=$mDroppedTrackballEvents" +
                    "flips=$mDroppedFlipEvents" +
                    "rotations=$mDroppedRotationEvents")
        }

        //report network stats
        mNetworkMonitor.dump()

        return if (crashedAtCycle < mCount - 1) {
            Logger.wLPrintln("System appears to have crashed at event $crashedAtCycle of $mCount" +
                    " using seed $mSeed")
            crashedAtCycle
        } else {
            if (mVerbose > 0) {
                Logger.lPrintln("// Monkey finished")
            }
            0
        }
    }

    /**
     * Process the command-line options
     *
     * @return Returns true if options were parsed with no apparent errors
     * */

    private fun processOptions(): Boolean {
        //quick (throwaway) check for unadorned command
        if (mArgs!!.isEmpty()) {
            showUsage()
            return false
        }
        mArgs!!.forEach{ print("$it ")}
        println()

        val validPackages = mutableSetOf<String>()

        //parse options for Monkey and Ape for now, TODO: add ComboDroid extension if necessary
        mArgs!!.forEach(::println)
        try {
            var opt = nextOption()
            while (opt != null) {
                when(opt) {
                    "-s" -> mSeed = nextOptionLong("Seed")
                    "-p" -> validPackages.add(nextOptionData()!!)
                    "-c" -> mMainCategories.add(nextOptionData())
                    "-v" -> mVerbose += 1
                    "--ignore-crashes" -> mIgnoreCrashes = true
                    "--ignore-timeouts" -> mIgnoreTimeouts = true
                    "--ignore-security-exceptions" -> mIgnoreSecurityExceptions = true
                    "--monitor-native-crashes" -> mMonitorNativeCrashes = true
                    "--ignore-native-crashes" -> mIgnoreNativeCrashes = true
                    "--kill-process-after-error" -> mKillProcessAfterError = true
                    "hprof" -> mGenerateHprof = true

                    //a set of Ape options
//                    "--ape" -> {
//                        mUseApe = true
//                        agentType = nextOptionData()!!
//                    }
//                    "--ape-model" -> modelFile = nextOptionData()!!
//                    "--ape-replay" -> replayLog = nextOptionData()!!
//                    "--ape_clean" -> mApeCleanApp = true

                    "--running-minutes" -> mRunningMillis = nextOptionLong("Running Minutres") * ONE_MINUTE_IN_MILLISECOND
                    "--pct-touch" -> mFactors[MonkeySourceRandom.FACTOR_TOUCH] = (-nextOptionLong("touch events percentage")).toFloat()
                    "--pct-motion" ->  mFactors[MonkeySourceRandom.FACTOR_MOTION] = (-nextOptionLong("motion events percentage")).toFloat()
                    "--pct-trackball" ->  mFactors[MonkeySourceRandom.FACTOR_TRACKBALL] = (-nextOptionLong("trackball events percentage")).toFloat()
                    "--pct-rotation" ->  mFactors[MonkeySourceRandom.FACTOR_ROTATION] = (-nextOptionLong("screen rotation events percentage")).toFloat()
                    "--pct-syskeys" ->  mFactors[MonkeySourceRandom.FACTOR_SYSOPS] = (-nextOptionLong("system (key) operation percentage")).toFloat()
                    "--pct-nav" ->  mFactors[MonkeySourceRandom.FACTOR_NAV] = (-nextOptionLong("nav events percentage")).toFloat()
                    "--pct-majornav" ->  mFactors[MonkeySourceRandom.FACTOR_MAJORNAV] = (-nextOptionLong("major nav events percentage")).toFloat()
                    "--pct-appswitch" ->  mFactors[MonkeySourceRandom.FACTOR_APPSWITCH] = (-nextOptionLong("app switch events percentage")).toFloat()
                    "--pct-flip" ->  mFactors[MonkeySourceRandom.FACTOR_FLIP] = (-nextOptionLong("keyboard flip percentage")).toFloat()
                    //Any possible key event
                    "--pct-anyevent" ->  mFactors[MonkeySourceRandom.FACTOR_ANYTHING] = (-nextOptionLong("any events percentage")).toFloat()
                    "--pct-pinchzoom" ->  mFactors[MonkeySourceRandom.FACTOR_PINCHZOOM] = (-nextOptionLong("pinch zoom events percentage")).toFloat()
                    "--pct-permission" ->  mFactors[MonkeySourceRandom.FACTOR_PERMISSION] = (-nextOptionLong("runtime permission toggle events percentage")).toFloat()
                    "--pkg-blacklist-file" -> mPkgBlacklistFile = nextOptionData()
                    "--pkg-whitelist-file" -> mPkgWhitelistFile = nextOptionData()
                    "--throttle" -> mThrottle = nextOptionLong("delay (in milliseconds) to wait between events")
                    "--randomize-throttle" -> mRandomizeThrottle = true
                    "--wait-dbg" -> {/*do nothing, it's caught at the very start of run()*/}
                    "--dbg-no-events" -> mSendNoEvents = true
                    "--port" -> mServerPort = nextOptionLong("Server port to listen on for commands").toInt()
                    "--setup" -> mSetupFileName = nextOptionData()
                    "-f" -> mScriptFileNames.add(nextOptionData())
                    "--profile-wait" -> mProfileWaitTime = nextOptionLong("Profile delay (in milliseconds) to wait between user action")
                    "--device-sleep-time" -> mDeviceSleepTime = nextOptionLong("Device sleep time (in milliseconds)")
                    "--randomize-script" -> mRandomizeScript = true
                    "--script-log" -> mScriptLog = true
                    "--bugreport" -> mRequestBugreport = true
                    "--periodic-bugreport" -> {
                        mGetPeriodicBugreport = true
                        mBugreportFrequency = nextOptionLong("Number of iterations")
                    }
                    "--permission-target-system" -> mPermissionTargetSystem = true
                    "-h" -> {
                        showUsage()
                        return false
                    }
                    else -> {
                        Logger.wLPrintln("** Error: Unknown option: $opt")
                        showUsage()
                        return false
                    }
                }
                opt = nextOption()
            }
            MonkeyUtils.sFilter.addValidPackages(validPackages.toSet())
        } catch (ex : RuntimeException) {
            Logger.wLPrintln("** Error: $$ex")
            ex.printStackTrace()
            showUsage()
            return false
        }

        //If a server port hasn't been specified, we need to specify a count
        if(mServerPort == -1) {
            if (mRunningMillis < 0) { // Ignore count if running time is specified
                val countStr: String? = nextArg()
                if (countStr == null) {
                    Logger.wLPrintln("** Error: Count not specified")
                    showUsage()
                    return false
                }

                val paredCount = countStr.toIntOrNull()
                if (paredCount != null) {
                    mCount = paredCount
                } else {
                    Logger.wLPrintln("** Error: Count is not a number")
                    showUsage()
                    return false
                }
            }
        }

        //Ape ignores all native crashes
        if (mUseApe) {
            mIgnoreNativeCrashes = true
        }

        val sb = StringBuilder("/sdcard/sata-").apply {
            validPackages.forEach {
                append("$it-")
            }
            if (mUseApe) {append("ape-$mApeAgent")}
            else {append("monkey-")}

            if (mRunningMillis > 0){
                append("-running-minutes-${mRunningMillis / ONE_MINUTE_IN_MILLISECOND}")
            } else {
                append("-count-$mCount")
            }
        }
        mOutputDirectory = createOutputDirectory(sb.toString())
        return true
    }


    /**
     * Print how to use this command.
     */
    private fun showUsage() {
        val usage = StringBuffer()
        usage.append("usage: monkey [-p ALLOWED_PACKAGE [-p ALLOWED_PACKAGE] ...]\n")
        usage.append("              [-c MAIN_CATEGORY [-c MAIN_CATEGORY] ...]\n")
        usage.append("              [--ignore-crashes] [--ignore-timeouts]\n")
        usage.append("              [--ignore-security-exceptions]\n")
        usage.append("              [--ape [AGENT_TYPE(random,sata)]]\n")
        usage.append("              [--running-minutes MINUTES\n")
        usage.append("              [--monitor-native-crashes] [--ignore-native-crashes]\n")
        usage.append("              [--kill-process-after-error] [--hprof]\n")
        usage.append("              [--pct-touch PERCENT] [--pct-motion PERCENT]\n")
        usage.append("              [--pct-trackball PERCENT] [--pct-syskeys PERCENT]\n")
        usage.append("              [--pct-nav PERCENT] [--pct-majornav PERCENT]\n")
        usage.append("              [--pct-appswitch PERCENT] [--pct-flip PERCENT]\n")
        usage.append("              [--pct-anyevent PERCENT] [--pct-pinchzoom PERCENT]\n")
        usage.append("              [--pct-permission PERCENT]\n")
        usage.append("              [--pkg-blacklist-file PACKAGE_BLACKLIST_FILE]\n")
        usage.append("              [--pkg-whitelist-file PACKAGE_WHITELIST_FILE]\n")
        usage.append("              [--wait-dbg] [--dbg-no-events]\n")
        usage.append("              [--setup scriptfile] [-f scriptfile [-f scriptfile] ...]\n")
        usage.append("              [--port port]\n")
        usage.append("              [-s SEED] [-v [-v] ...]\n")
        usage.append("              [--throttle MILLISEC] [--randomize-throttle]\n")
        usage.append("              [--profile-wait MILLISEC]\n")
        usage.append("              [--device-sleep-time MILLISEC]\n")
        usage.append("              [--randomize-script]\n")
        usage.append("              [--script-log]\n")
        usage.append("              [--bugreport]\n")
        usage.append("              [--periodic-bugreport]\n")
        usage.append("              [--permission-target-system]\n")
        usage.append("              COUNT\n")
        System.err.println(usage.toString())
    }

    /**
     * Return the next command line option. This has a number of special cases
     * which closely, but not exactly, follow the POSIX command line options
     * patterns:
     *
     * <pre>
     * -- means to stop processing additional options
     * -z means option z
     * -z ARGS means option z with (non-optional) arguments ARGS
     * -zARGS means option z with (optional) arguments ARGS
     * --zz means option zz
     * --zz ARGS means option zz with (non-optional) arguments ARGS
     * </pre>
     *
     * Note that you cannot combine single letter options; -abc != -a -b -c
     *
     * @return Returns the option string, or null if there are no more options.
     */

    private fun nextOption(): String? {
        val mArgsNotNull = mArgs!!
        if (mNextArg >= mArgsNotNull.size) {return null}
        val arg = mArgsNotNull[mNextArg]!!
        if (!arg.startsWith("-")) {return null}
        mNextArg++
        if (arg == "--") {return null}
        if (arg.length > 1 && arg[1] != '-') {
            return if (arg.length > 2) {
                mCurArgData = arg.substring(2)
                arg.substring(0,2)
            } else {
                mCurArgData = null
                arg
            }
        }
        mCurArgData = null
        return arg
    }

    /**
     * Returns the next data associated with the current option.
     *
     * @param opt
     *          The name of the option
     * @return Returns the data string, or null of there are no more arguments
     * */
    private fun nextOptionData(): String? = when {
        mCurArgData != null -> mCurArgData
        mNextArg >= mArgs!!.size -> null
        else -> mArgs!![mNextArg++]
    }

    /**
     * Returns a long converted from the next data argument, with error handling
     * if not available
     *
     * @param opt
     *          The name of the option
     * @return Returns a long converted from the argument
     * */
    private fun nextOptionLong(opt: String) : Long =
        try {
            nextOptionData()!!.toLong()
        } catch (e: Exception) {
            Logger.wLPrintln("** Error: $opt is not a number")
            throw e
        }

    /**
     * Return the next argument on the command line
     *
     * @return Returns the argument string, or null if we have reached the end
     * */
    private fun nextArg() : String? =
        if (mNextArg >= mArgs!!.size) {
            null
        } else {
            mArgs!![mNextArg++]
        }

    /**
     * Create the output directory at the specified location on the phone for Ape
     *
     * @param path
     *                  The path of created directory
     * */
    fun createOutputDirectory(path: String) : File {
        val dir = File(path)
        // if dir already exists, move this one to another directory
        if (dir.exists()) {
            var count = 1
            var newDir = File("$path.${count++}")
            while (newDir.exists()) {
                newDir = File("$path.${count++}")
            }
            Logger.lPrintln("Rename the original $dir to $newDir")
            dir.renameTo(newDir)
        }
        if (dir.exists()) {
            throw IllegalAccessException("Cannot rename directory $dir")
        }

        if (!dir.mkdirs()) {
            throw IllegalAccessException("Cannot mkdirs at $dir")
        }
        return dir
    }

    /**
     * Load package blacklist or whitelist (if specified).
     *
     * @return Returns false if any error occurs.
     */
    private fun loadPackageLists(): Boolean {
        if ((mPkgWhitelistFile != null || MonkeyUtils.sFilter.hasValidPackages()) && mPkgBlacklistFile != null) {
            System.err.println("** Error: you can not specify a package blacklist " + "together with a whitelist or individual packages (via -p).")
            return false
        }
        val validPackages = java.util.HashSet<String>()
        if (mPkgWhitelistFile != null && !loadPackageListFromFile(mPkgWhitelistFile!!, validPackages)) {
            return false
        }
        MonkeyUtils.sFilter.addValidPackages(validPackages)
        val invalidPackages = java.util.HashSet<String>()
        if (mPkgBlacklistFile != null && !loadPackageListFromFile(mPkgBlacklistFile!!, invalidPackages)) {
            return false
        }
        MonkeyUtils.sFilter.addInvalidPackages(invalidPackages)
        return true
    }
    /**
     * Load a list of package names from a file.
     *
     * @param fileName
     * The file name, with package names separated by new line.
     * @param list
     * The destination list.
     * @return Returns false if any error occurs.
     */
    private fun loadPackageListFromFile(fileName: String, list: MutableSet<String>): Boolean =
        try {
            File(fileName).readLines().forEach { s ->
                val ss = s.trim()
                if (ss.isNotEmpty() && !ss.startsWith("#")) {
                    list.add(ss)
                }
            }
            true
        } catch (ioe: IOException) {
            System.err.println(ioe)
            false
        }

    /**
     * Check for any internal configuration (primarily build-time) errors.
     *
     * @return Returns true if ready to rock.
     */
    private fun checkInternalConfiguration() = true


    /**
     * Attach to the required system interfaces.
     *
     * @return Returns true if all system interfaces were available.
     */
    //TODO: in ape style
    private fun getSystemInterfaces(): Boolean {
        //TODO:
        mAm = ActivityManagerNative.getDefault();
        //mAm = ApeAPIAdapter.getActivityManager()
        if (mAm == null) {
            System.err.println("** Error: Unable to connect to activity manager; is the system " + "running?")
            return false
        }

        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"))
        if (mWm == null) {
            System.err.println("** Error: Unable to connect to window manager; is the system " + "running?")
            return false
        }

        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
        if (mPm == null) {
            System.err.println("** Error: Unable to connect to package manager; is the system " + "running?")
            return false
        }

        try {
            if (mUseApe) {
                //TODO:
                //ApeAPIAdapter.setActivityController(mAm, ApeActivityController())
               // mAm!!.setActivityController(ApeActivityController());
            } else {
                //TODO:
                //ApeAPIAdapter.setActivityController(mAm, ActivityController())
                 mAm!!.setActivityController(ActivityController());
            }
            mNetworkMonitor.register(mAm!!)
        } catch (e: RemoteException) {
            System.err.println("** Failed talking with activity manager!")
            return false
        }

        return true
    }

    /**
     * Monitor operations happening in the system.
     */
    private open inner class ActivityController : IActivityController.Stub() {
        override fun appCrashed(processName: String?, pid: Int, shortMsg: String?, longMsg: String?, timeMillis: Long, stackTrace: String?): Boolean {
            val savedPolicy = StrictMode.allowThreadDiskWrites()
            System.err.println("// CRASH: $processName (pid $pid)")
            System.err.println("// Short Msg: $shortMsg")
            System.err.println("// Long Msg: $longMsg")
            System.err.println("// Build Label: " + Build.FINGERPRINT)
            System.err.println("// Build Changelist: " + Build.VERSION.INCREMENTAL)
            System.err.println("// Build Time: " + Build.TIME)
            System.err.println("// " + stackTrace?.replace("\n", "\n// "))
            StrictMode.setThreadPolicy(savedPolicy)

            if (!mIgnoreCrashes || mRequestBugreport) {
                lock.withLock {
                    if (!mIgnoreCrashes) {
                        mAbort = true
                    }
                    if (mRequestBugreport) {
                        mRequestAppCrashBugreport = true
                        mReportProcessName = processName
                    }
                }
                return !mKillProcessAfterError
            }
            return false
        }

        override fun activityResuming(pkg: String?): Boolean {
            val savedPolicy = StrictMode.allowThreadDiskWrites()
            println("    // activityResuming($pkg)")
            val allow = MonkeyUtils.sFilter.checkEnteringPackage(pkg!!) || DEBUG_ALLOW_ANY_RESTARTS != 0
            if (!allow) {
                if (mVerbose > 0) {
                    Logger.lPrintln("    // ${(if (allow) "Allowing" else "Rejecting")} resume of package $pkg")
                }
            }
            currentPackage = pkg
            StrictMode.setThreadPolicy(savedPolicy)
            return allow
        }

        override fun appEarlyNotResponding(p0: String?, p1: Int, p2: String?): Int = 0

        override fun systemNotResponding(message: String?): Int {
            val savedPolicy = StrictMode.allowThreadDiskWrites()
            System.err.println("// WATCHDOG: $message")
            StrictMode.setThreadPolicy(savedPolicy)

            lock.withLock{
                if (!mIgnoreCrashes) {
                    mAbort = true
                }
                if (mRequestBugreport) {
                    mRequestWatchdogBugreport = true
                }
                mWatchdogWaiting = true
            }
            lock.withLock {
                while (mWatchdogWaiting) {
                    try {
                        condition.await()
                    } catch (e: InterruptedException) {
                    }

                }
            }
            return if (mKillProcessAfterError) -1 else 1
        }

        override fun activityStarting(intent: Intent?, pkg: String?): Boolean {
            val allow = MonkeyUtils.sFilter.checkEnteringPackage(pkg!!) || (DEBUG_ALLOW_ANY_STARTS != 0)
            if (mVerbose > 0) {
                // StrictMode's disk checks end up catching this on
                // userdebug/eng builds due to PrintStream going to a
                // FileOutputStream in the end (perhaps only when
                // redirected to a file?) So we allow disk writes
                // around this region for the monkey to minimize
                // harmless dropbox uploads from monkeys.
                val savedPolicy = StrictMode.allowThreadDiskWrites()
                Logger.lPrintln("// ${if (allow) "Allowing" else "Rejecting"} start of $intent in package $pkg")
                StrictMode.setThreadPolicy(savedPolicy)
            }
            currentPackage = pkg
            currentIntent = intent!!
            return  allow
        }

        override fun appNotResponding(processName: String?, pid: Int, processStats: String?): Int {
            val savedPolicy = StrictMode.allowThreadDiskWrites()
            System.err.println("// NOT RESPONDING: $processName (pid $pid)")
            System.err.println(processStats)
            StrictMode.setThreadPolicy(savedPolicy)

            lock.withLock {
                mRequestAnrTraces = true
                mRequestDumpsysMemInfo = true
                mRequestProcRank = true
                if (mRequestBugreport) {
                    mRequestAnrBugreport = true
                    mReportProcessName = processName
                }
            }
            if (!mIgnoreTimeouts) {
                lock.withLock {
                    mAbort = true
                }
            }
            return if (mKillProcessAfterError) -1 else 1
        }
    }

    /**
     * Using the restrictions provided (categories & packages), generate a list
     * of activities that we can actually switch to.
     *
     * @return Returns true if it could successfully build a list of target
     *         activities
     */
    private fun getMainApps() : Boolean {
        try {
            mMainCategories.forEach {
                if (it == null) return@forEach
                val intent = Intent(Intent.ACTION_MAIN)
                if (it.isNotEmpty()) {
                    intent.addCategory(it)
                }
                //TODO
                val mainApps = mPm!!.queryIntentActivities(intent, null, 0, UserHandle.myUserId())
                //val mainApps = ApeAPIAdapter.queryIntentActivities(mPm, intent, UserHandle.myUserId())
                if (mainApps == null || mainApps.isEmpty()) {
                    Logger.lPrintln("// Warning: no activities found for category $it")
                    return@forEach
                }
                if (mVerbose >= 2) { Logger.lPrintln("// Selecting main activities from category $it")}
                mainApps.forEach { r ->
                    val packageName = r.activityInfo.applicationInfo.packageName
                    if (MonkeyUtils.sFilter.checkEnteringPackage(packageName)) {
                        if (mVerbose >= 2 ) {// very verbose
                            Logger.lPrintln("// + Using main activity ${r.activityInfo.name} from package " +
                                    "$packageName)")
                        }
                        mUids.add(r.activityInfo.applicationInfo.uid)
                        mMainApps.add(ComponentName(packageName, r.activityInfo.name))
                    } else{
                        if (mVerbose >= 3) { //very very verbose
                            Logger.lPrintln("// - NOT USING main activity ${r.activityInfo.name} from package" +
                                    "$packageName)")
                        }
                    }
                }
            }
        } catch (e: RemoteException) {
            System.err.println("** Failed talking with package manager!")
        }

        return if (mMainApps.isEmpty()) {
            Logger.lPrintln("** No activities found to run monkey aborted")
            false
        } else true
    }

    /**
     * Send SIGNAL_USR1 to all processes. This will generate large (5mb)
     * profiling reports in data/misc, so use with care.
     */
    private fun signalPersistentProcesses() {
        try {
            mAm!!.signalPersistentProcesses(Process.SIGNAL_USR1)

            lock.withLock {
                condition.await(2000L, TimeUnit.MILLISECONDS)
            }
        } catch (e: RemoteException) {
            System.err.println("** Failed talking with activity manager!")
        } catch (e: InterruptedException) {
        }

    }

    /**
     * Run mCount cycles and see if we hit any crashers.
     * <p>
     * TODO: Meta state on keys
     *
     * @return Returns the last cycle which executed. If the value == mCount, no
     *         errors detected.
     */
    private fun runMonkeyCycles() : Int {
        var eventCounter = 0
        var cycleCounter = 0
        var shouldReportAnrTraces = false
        var shouldReportDumpsysMemInfo = false
        var shouldAbort = false
        var systemCrashed = false

        var currentTime = SystemClock.elapsedRealtime()

        if (mRunningMillis > 0) {
            mEndTime = currentTime + mRunningMillis

            //We report everything
            mIgnoreCrashes = false
            mIgnoreTimeouts = false
            mKillProcessAfterError = true
        }

        /**
         * The main loop to generate events
         */

        //TO DO: The count should apply to each of the script file
        while (!systemCrashed) {
            //check user specified stopping condition
            if (mRunningMillis < 0) {
                if (cycleCounter >= mCount) break
            } else {
                currentTime = SystemClock.elapsedRealtime()
                if (currentTime > mEndTime) break
            }

            lock.withLock {
                if (mRequestProcRank) {
                    reportProcRank()
                    mRequestProcRank = false
                }
                if (mRequestAnrTraces) {
                    mRequestAnrTraces = false
                    shouldReportAnrTraces = true
                }
                if (mRequestAnrBugreport) {
                    getBugreport("anr_" + mReportProcessName + "_")
                    mRequestAnrBugreport = false
                }
                if (mRequestWatchdogBugreport) {
                    println("Print the watchdog report")
                    getBugreport("anr_watchdog_")
                    mRequestWatchdogBugreport = false
                }
                if (mRequestAppCrashBugreport) {
                    getBugreport("app_crash" + mReportProcessName + "_")
                    mRequestAppCrashBugreport = false
                }
                if (mRequestPeriodicBugreport) {
                    getBugreport("Bugreport_")
                    mRequestPeriodicBugreport = false
                }
                if (mRequestDumpsysMemInfo) {
                    mRequestDumpsysMemInfo = false
                    shouldReportDumpsysMemInfo = true
                }
                if (mMonitorNativeCrashes) {
                    mMonitorNativeCrashes = false
                    // first time through, when eventCounter == 0, just set up
                    // the watcher (ignore the error)
                    if (checkNativeCrashes() && eventCounter > 0) {
                        println("** New native crash detected.")
                        if (mRequestBugreport) {
                            getBugreport("native_crash_")
                        }
                        mAbort = mAbort || !mIgnoreNativeCrashes || mKillProcessAfterError
                    }
                }
                if (mAbort) {
                    shouldAbort = true
                }
                if (mWatchdogWaiting) {
                    mWatchdogWaiting = false
                    condition.signalAll()
                }
            }

            // Report ANR, dumpsys after releasing lock on this.
            // This ensures the availability of the lock to Activity
            // controller's appNotResponding
            if (shouldReportAnrTraces) {
                shouldReportAnrTraces = false
                reportAnrTraces()
            }

            if (shouldReportDumpsysMemInfo) {
                shouldReportDumpsysMemInfo = false
                reportDumpsysMemInfo()
            }

            if (shouldAbort) {
                shouldAbort = false
                if (mRunningMillis > 0) {
                    mAbort = false
                    Logger.lPrintln("Encounter abort but we are in continuous mode.")
                } else {
                    println("** Monkey aborted due to error.")
                    println("Events injected: $eventCounter")
                    return eventCounter
                }
            }

            // In this debugging mode, we never send any events. This is
            // primarily here so you can manually test the package or category
            // limits, while manually exercising the system.
            if (mSendNoEvents) {
                eventCounter++
                cycleCounter++
                continue
            }

            if (mVerbose > 0 && eventCounter % 100 == 0 && eventCounter != 0) {
                val calendarTime = MonkeyUtils.toCalendarTime(System.currentTimeMillis())
                val systemUpTime = SystemClock.elapsedRealtime()
                Logger.lPrintln("    //[calendar_time:$calendarTime system_uptime:$systemUpTime]")
                Logger.lPrintln("    // Sending event #$eventCounter")
            }

            //get the next event
            val ev = mEventSource.getNextEvent()
            if (ev != null) {
                var failed = false
                when(ev.injectEvent(mWm!!, mAm!!, mVerbose)) {
                    MonkeyEvent.INJECT_FAIL -> {
                        for (i in 0..4){
                            if (mVerbose >= 1) {
                                Logger.iLPrintln("Injection Faild, retry No. $i")
                            }
                            Thread.sleep(50)
                            if (ev.injectEvent(mWm!!,mAm!!,mVerbose) != MonkeyEvent.INJECT_FAIL)
                                break
                        }
                        Logger.lPrintln("   //Injection Failed ${ev.eventType} ${ev.eventId}, Given up")
                        failed = true
                        when (ev) {
                            is MonkeyKeyEvent -> mDroppedKeyEvents++
                            is MonkeyMotionEvent -> mDroppedPointerEvents++
                            is MonkeyFlipEvent -> mDroppedFlipEvents++
                            is MonkeyRotationEvent -> mDroppedRotationEvents++
                        }
                    }
                    MonkeyEvent.INJECT_ERROR_REMOTE_EXCEPTION -> {
                        systemCrashed = true
                        System.err.println("** Error: RemoteException while injecting event.")
                    }
                    MonkeyEvent.INJECT_ERROR_SECURITY_EXCEPTION -> {
                        systemCrashed = !mIgnoreSecurityExceptions
                    }
                }

                // Don't count throttling as an event
                if (ev !is MonkeyThrottleEvent && !failed) {
                    eventCounter++
                    if (mCountEvents){
                        cycleCounter++
                    }
                }
            } else {
                if (!mCountEvents) {
                    cycleCounter++
                    writeScriptLog(cycleCounter)
                    // Capture the bugreport after n iteration
                    if (mGetPeriodicBugreport) {
                        if (cycleCounter % mBugreportFrequency == 0L) {
                            mRequestPeriodicBugreport = true
                        }
                    }
                } else {
                    // Event Source has signaled that we have no more events to
                    // process
                    break
                }
            }
        }
        Logger.lPrintln("Events injected: $eventCounter")
        return eventCounter
    }

    /**
     * Run the procrank tool to insert system status information into the debug
     * report.
     */
    private fun reportProcRank() {
        commandLineReport("procrank", "procrank")
    }

    // Write the bugreport to the sdcard.
    private fun getBugreport(reportName: String) {
        var _reportName = reportName
        _reportName += MonkeyUtils.toCalendarTime(System.currentTimeMillis())
        val bugreportName = _reportName.replace("[ ,:]".toRegex(), "_")
        commandLineReport("$bugreportName.txt", "bugreport")
    }

    /**
     * Run "cat /data/anr/traces.txt". Wait about 5 seconds first, to let the
     * asynchronous report writing complete.
     */
    private fun reportAnrTraces() {
        try {
            Thread.sleep((5 * 1000).toLong())
        } catch (e: InterruptedException) {
        }

        commandLineReport("anr traces", "cat /data/anr/traces.txt")
    }

    /**
     * Run "dumpsys meminfo"
     *
     *
     * NOTE: You cannot perform a dumpsys call from the ActivityController
     * callback, as it will deadlock. This should only be called from the main
     * loop of the monkey.
     */
    private fun reportDumpsysMemInfo() {
        commandLineReport("meminfo", "dumpsys meminfo")
    }
    /**
     * Print report from a single command line.
     *
     *
     * TODO: Use ProcessBuilder & redirectErrorStream(true) to capture both
     * streams (might be important for some command lines)
     *
     * @param reportName
     * Simple tag that will print before the report and in various
     * annotations.
     * @param command
     * Command line to execute.
     */
    private fun commandLineReport(reportName: String, command: String) {
        System.err.println("$reportName:")
       // val rt = Runtime.getRuntime()
        var logOutput: Writer? = null

        try {
            // Process must be fully qualified here because android.os.Process
            // is used elsewhere
            val p = Runtime.getRuntime().exec(command)

            if (mRequestBugreport) {
                logOutput = BufferedWriter(
                    FileWriter(File(Environment.getLegacyExternalStorageDirectory(), reportName), true)
                )
            }
            // pipe everything from process stdout -> System.err
            val inStream = p.inputStream
            val inReader = InputStreamReader(inStream)
            val inBuffer = BufferedReader(inReader)
            val errBuffer = BufferedReader(InputStreamReader(p.errorStream))
            var s: String? = inBuffer.readLine()
            while ( s != null) {
                if (mRequestBugreport) {
                    logOutput!!.write(s)
                    logOutput.write("\n")
                } else {
                    System.err.println(s)
                }
                s = inBuffer.readLine()
            }

            var ss : String? = errBuffer.readLine()
            while (ss != null) {
                ss = errBuffer.readLine()
            }

            val status = p.waitFor()
            System.err.println("// $reportName status was $status")

            logOutput?.close()
        } catch (e: Exception) {
            System.err.println("// Exception from $reportName:")
            System.err.println(e.toString())
        }
    }

    /**
     * Watch for appearance of new tombstone files, which indicate native
     * crashes.
     *
     * @return Returns true if new files have appeared in the list
     */
    private fun checkNativeCrashes(): Boolean {
        val tombstones = TOMBSTONES_PATH.list()

        // shortcut path for usually empty directory, so we don't waste even
        // more objects
        if (tombstones == null || tombstones.size == 0) {
            mTombstones = null
            return false
        }

        // use set logic to look for new files
        val newStones = java.util.HashSet<String>()
        for (x in tombstones) {
            newStones.add(x)
        }

        val result = mTombstones == null || !mTombstones!!.containsAll(newStones)

        // keep the new list for the next time
        mTombstones = newStones

        return result
    }

    // Write the numbe of iteration to the log
    private fun writeScriptLog(count: Int) {
        // TO DO: Add the script file name to the log.
        try {
            val output = BufferedWriter(
                FileWriter(File(Environment.getLegacyExternalStorageDirectory(), "scriptlog.txt"), true)
            )
            output.write(
                "iteration: " + count + " time: " + MonkeyUtils.toCalendarTime(System.currentTimeMillis()) + "\n"
            )
            output.close()
        } catch (e: IOException) {
            System.err.println(e.toString())
        }

    }

    /**
     * Used to monitor operations happening in the system.
     * Serve as a bridget between the Activity Manager with the agent,
     * who can then record information in an observer mode
     * */
//    private inner class ApeActivityController : ActivityController() {
//
//        /**
//         * @return true to allow it
//         * */
//        // (1) tell agent; (2) for recentsActivity, do nothing; (3) for packageinstaller, stop the app and grant all its request persmissions
//        // (so it won't go to this page again)
//        override fun activityStarting(intent: Intent?, pkg: String?): Boolean {
//            if (mUseApe) {
//                val agent = (mEventSource as MonkeySourceApe).getAgent()
//                agent.activityStarting(intent, pkg)
//
//                // com.android.systemui/.recents.RecentsActivity
//                if (pkg == "com.android.systemui") {
//                    if (intent?.component?.className == "com.android.systemui.RecentsActivity") {
//                        Logger.iLPrintln("Enable RecentsActivity")
//                        return true
//                    }
//                }
//                // com.android.packageinstaller/.permission.ui.GrantPermissionsActivity
//                if (pkg == "com.android.packageinstaller") {
//                    if (intent?.component?.className == "com.android.packageinstaller.permission.ui.GrantPermissionsActivity") {
//                        Logger.iLPrintln("Request permission: $intent")
//                        if (mUseApe) {
//                            (mEventSource as MonkeySourceApe).stopPackages()
//                            (mEventSource as MonkeySourceApe).grantRuntimePermissions("GrantPermissionsActivity")
//                        }
//                    }
//                }
//            }
//            return super.activityStarting(intent, pkg)
//        }
//        /***
//         * @return true to allow it
//         */
//
//        override fun activityResuming(pkg: String?): Boolean {
//            if (mUseApe) {
//                (mEventSource as MonkeySourceApe).getAgent().activityResuming(pkg)
//            }
//            return super.activityResuming(pkg)
//        }
//
//        /**
//         * @return true to allow normal error recovery (app crash dialog) to occur, false to kill it immediately
//         * */
//        override fun appCrashed(
//            processName: String?,
//            pid: Int,
//            shortMsg: String?,
//            longMsg: String?,
//            timeMillis: Long,
//            stackTrace: String?
//        ): Boolean {
//            val savePolicy = StrictMode.allowThreadDiskWrites()
//            Logger.lPrintln("// CRASH: $processName (pid $pid) (elapsed nanos: ${SystemClock.elapsedRealtimeNanos()})")
//            Logger.lPrintln("// Short Msg: $shortMsg")
//            Logger.lPrintln("// Long Msg: $longMsg")
//            Logger.lPrintln("// Build Label: " + Build.FINGERPRINT)
//            Logger.lPrintln("// Build Changelist: " + Build.VERSION.INCREMENTAL)
//            Logger.lPrintln("// Build Time: " + Build.TIME)
//            Logger.lPrintln("// " + stackTrace?.replace("\n", "\n" + Logger.TAG + "// "))
//
//            if (mEventSource is MonkeySourceApe) {
//                (mEventSource as MonkeySourceApe).getAgent().appCrashed(processName, pid, shortMsg, longMsg, timeMillis, stackTrace)
//            }
//
//            StrictMode.setThreadPolicy(savePolicy)
//
//            if (!mIgnoreCrashes || mRequestBugreport) {
//                Monkey.lock.withLock {
//                    if (!mIgnoreCrashes) {
//                        mAbort = true
//                    }
//                    if (mRequestBugreport) {
//                        mRequestAppCrashBugreport = true
//                        mReportProcessName = processName
//                    }
//                }
//                return !mKillProcessAfterError
//            }
//            // kill by default
//            return false
//        }
//
//        override fun appEarlyNotResponding(processName: String?, pid: Int, annotation: String?): Int {
//            if (mUseApe) {
//                (mEventSource as MonkeySourceApe).getAgent().appEarlyNotResponding(processName, pid, annotation)
//            }
//            return super.appEarlyNotResponding(processName, pid, annotation)
//        }
//
//        override fun appNotResponding(processName: String?, pid: Int, processStats: String?): Int {
//            if (mUseApe){
//                (mEventSource as MonkeySourceApe).getAgent().appNotResponding(processName, pid, processStats)
//            }
//            return super.appNotResponding(processName, pid, processStats)
//        }
//    }
}


