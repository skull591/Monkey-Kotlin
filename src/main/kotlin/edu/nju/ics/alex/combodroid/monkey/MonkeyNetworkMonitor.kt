package edu.nju.ics.alex.combodroid.monkey

import android.app.IActivityManager
import android.content.Context
import android.content.IIntentReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.os.SystemClock
import android.os.UserHandle
import edu.nju.ics.alex.combodroid.utils.Logger
import sun.nio.ch.Net
import sun.rmi.runtime.Log

/**
 * Class for monitoring network connectivity during monkey runs.
 */
class MonkeyNetworkMonitor : IIntentReceiver.Stub() {
    private val LDEBUG = false
    private val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
    private var mCollectionStartTime: Long = 0 // time we started collecting data
    private var mEventTime: Long = 0 // time of last event (connect, disconnect, etc.)
    private var mLastNetworkType = -1 // unknown
    private var mWifiElapsedTime: Long = 0 // accumulated time spent on wifi since
    // start()
    private var mMobileElapsedTime: Long = 0 // accumulated time spent on mobile
    // since start()
    private var mElapsedTime: Long = 0 // amount of time spent between start() and
    // stop()

    override fun performReceive(intent: Intent?, resultCode: Int, data: String?, extras: Bundle?, ordered: Boolean, sticky: Boolean, sendingUser: Int) {
        val ni = intent!!.getParcelableExtra<NetworkInfo>(ConnectivityManager.EXTRA_NETWORK_INFO)
        if (LDEBUG) { Logger.lPrintln("Network state changed: type=${ni.type}, state=${ni.state}")}
        updateNetworkStats()
        if (NetworkInfo.State.CONNECTED == ni.state) {
            if (LDEBUG) {Logger.lPrintln("Network connected")}
            mLastNetworkType = ni.type
        } else if (NetworkInfo.State.DISCONNECTED == ni.state) {
            if (LDEBUG) {Logger.lPrintln("Network not connected")}
            mLastNetworkType = -1
        }
        mEventTime = SystemClock.elapsedRealtime()
    }

    private fun updateNetworkStats() {
        val timeNow = SystemClock.elapsedRealtime()
        val delta = timeNow - mEventTime
        when(mLastNetworkType) {
            ConnectivityManager.TYPE_MOBILE -> {
                if (LDEBUG) { Logger.lPrintln("Adding to mobile: $delta") }
                mMobileElapsedTime += delta
            }
            ConnectivityManager.TYPE_WIFI -> {
                if (LDEBUG) { Logger.lPrintln("Adding to wifi: $delta") }
                mWifiElapsedTime += delta
            }
            else -> {
                if (LDEBUG) { Logger.lPrintln("Unaccounted for: $delta") }
            }
        }
        mElapsedTime = timeNow - mCollectionStartTime
    }

    fun start() {
        mWifiElapsedTime = 0
        mMobileElapsedTime = 0
        mElapsedTime = 0
        mCollectionStartTime = SystemClock.elapsedRealtime()
        mEventTime = mCollectionStartTime
    }
    fun stop() {
        updateNetworkStats()
    }

    fun register(am: IActivityManager) {
        if (LDEBUG)
            Logger.lPrintln("registering Receiver")
        //TODO: using ape style
         am.registerReceiver(null, null, this, filter, null, UserHandle.USER_ALL);
        //ApeAPIAdapter.registerReceiver(am, this, filter, UserHandle.USER_ALL)
    }

    fun unregister(am: IActivityManager) {
        if (LDEBUG)
            Logger.lPrintln("unregistering Receiver")
        am.unregisterReceiver(this)
    }

    fun dump() {
        Logger.lPrintln("## Network stats: elapsed time=${mElapsedTime}ms (${mMobileElapsedTime}ms mobile," +
                " ${mWifiElapsedTime}ms wifi, ${mElapsedTime - mMobileElapsedTime - mWifiElapsedTime}ms not connected")
    }

}