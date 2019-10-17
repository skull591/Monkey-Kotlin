package edu.nju.ics.alex.combodroid.monkey

import kotlin.random.Random


/**
 * Class for generating MonkeyEvents from multiple scripts.
 */
class MonkeySourceRandomScript
/**
 * Creates a MonkeySourceRandomScript instance with an additional setup
 * script.
 *
 * @param setupFileName
 * The name of the setup script file on the device.
 * @param scriptFileNames
 * An ArrayList of the names of the script files to be run
 * randomly.
 * @param throttle
 * The amount of time to sleep in ms between events.
 * @param randomizeThrottle
 * Whether to randomize throttle.
 * @param mRandom
 * The random number generator.
 */
    (
    setupFileName: String?, scriptFileNames: ArrayList<String?>, throttle: Long,
    randomizeThrottle: Boolean,
    /** The random number generator  */
    private val mRandom: Random, profileWaitTime: Long, deviceSleepTime: Long,
    randomizeScript: Boolean
) : MonkeyEventSource {
    /** The verbose level of the source (currently not used)  */
    private var mVerbose = 0

    /** The source for the setup script if it exists  */
    private var mSetupSource: MonkeySourceScript? = null

    /** The list of MonkeySourceScript instances to be played in random order  */
    private val mScriptSources = ArrayList<MonkeySourceScript>()

    /** The current source, set to the setup source and then a random script  */
    private var mCurrentSource: MonkeySourceScript? = null

    private var mRandomizeScript = false

    private var mScriptCount = 0


    init {
        if (setupFileName != null) {
            mSetupSource = MonkeySourceScript(
                mRandom, setupFileName, throttle, randomizeThrottle, profileWaitTime,
                deviceSleepTime
            )
            mCurrentSource = mSetupSource
        }

        for (fileName in scriptFileNames) {
            mScriptSources.add(
                MonkeySourceScript(
                    mRandom, fileName!!, throttle, randomizeThrottle, profileWaitTime,
                    deviceSleepTime
                )
            )
        }
        mRandomizeScript = randomizeScript
    }

    /**
     * Creates a MonkeySourceRandomScript instance without an additional setup
     * script.
     *
     * @param scriptFileNames
     * An ArrayList of the names of the script files to be run
     * randomly.
     * @param throttle
     * The amount of time to sleep in ms between events.
     * @param randomizeThrottle
     * Whether to randomize throttle.
     * @param random
     * The random number generator.
     */
    constructor(
        scriptFileNames: ArrayList<String?>, throttle: Long, randomizeThrottle: Boolean,
        random: Random, profileWaitTime: Long, deviceSleepTime: Long, randomizeScript: Boolean
    ) : this(
        null, scriptFileNames, throttle, randomizeThrottle, random, profileWaitTime, deviceSleepTime,
        randomizeScript
    )

    /**
     * Sets the verbosity for the source as well as all sub event sources.
     *
     * @param verbose
     * The verbose level.
     */
    override fun setVerbose(verbose: Int) {
        mVerbose = verbose

        if (mSetupSource != null) {
            mSetupSource!!.setVerbose(verbose)
        }

        for (source in mScriptSources) {
            source.setVerbose(verbose)
        }
    }

    /**
     * Validates that all the underlying event sources are valid
     *
     * @return True if all the script files are valid.
     *
     * @see MonkeySourceScript.validate
     */
    override fun validate(): Boolean {
        if (mSetupSource != null && !mSetupSource!!.validate()) {
            return false
        }

        for (source in mScriptSources) {
            if (!source.validate()) {
                return false
            }
        }

        return true
    }

    /**
     * Gets the next event from the current event source. If the event source is
     * null, a new script event source is chosen randomly from the list of
     * script sources and the next event is chosen from that.
     *
     * @return The first event in the event queue or null if the end of the file
     *         is reached or if an error is encountered reading the file.
     */
    override fun getNextEvent(): MonkeyEvent? {
        if (mCurrentSource == null) {
            val numSources = mScriptSources.size
            if (numSources == 1) {
                mCurrentSource = mScriptSources[0]
            } else if (numSources > 1) {
                if (mRandomizeScript) {
                    mCurrentSource = mScriptSources[mRandom.nextInt(numSources)]
                } else {
                    mCurrentSource = mScriptSources[mScriptCount % numSources]
                    mScriptCount++
                }
            }
        }
        if (mCurrentSource != null) {
            val nextEvent = mCurrentSource!!.getNextEvent()
            if (nextEvent == null) { mCurrentSource = null }
            return nextEvent
        }
        return null
    }
}
