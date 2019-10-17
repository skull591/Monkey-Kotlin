package edu.nju.ics.alex.combodroid.monkey

import java.text.SimpleDateFormat
import java.util.HashSet

/**
 * Misc utilities.
 */
object MonkeyUtils {

    private val DATE = java.util.Date()
    private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ")
    //objects are naturally late initialized
    val sFilter = PackageFilter


    /**
     * Return calendar time in pretty string.
     */
    @Synchronized
    fun toCalendarTime(time: Long): String {
        DATE.time = time
        return DATE_FORMATTER.format(DATE)
    }

}

object PackageFilter {
    private val mValidPackages = HashSet<String>()
    private val mInvalidPackages = HashSet<String>()

    fun addValidPackages(validPackages: Set<String>) {
        mValidPackages.addAll(validPackages)
    }

    fun addInvalidPackages(invalidPackages: Set<String>) {
        mInvalidPackages.addAll(invalidPackages)
    }

    fun hasValidPackages(): Boolean {
        return mValidPackages.size > 0
    }

    fun isPackageValid(pkg: String): Boolean {
        return mValidPackages.contains(pkg)
    }

    fun isPackageInvalid(pkg: String): Boolean {
        return mInvalidPackages.contains(pkg)
    }

    /**
     * Check whether we should run against the given package.
     *
     * @param pkg
     * The package name.
     * @return Returns true if we should run against pkg.
     */
    fun checkEnteringPackage(pkg: String): Boolean {
        if (mInvalidPackages.size > 0) {
            if (mInvalidPackages.contains(pkg)) {
                return false
            }
        } else if (mValidPackages.size > 0) {
            if (!mValidPackages.contains(pkg)) {
                return false
            }
        }
        return true
    }

    fun dump() {
        if (mValidPackages.size > 0) {
            val it = mValidPackages.iterator()
            while (it.hasNext()) {
                println(":AllowPackage: " + it.next())
            }
        }
        if (mInvalidPackages.size > 0) {
            val it = mInvalidPackages.iterator()
            while (it.hasNext()) {
                println(":DisallowPackage: " + it.next())
            }
        }
    }
}
