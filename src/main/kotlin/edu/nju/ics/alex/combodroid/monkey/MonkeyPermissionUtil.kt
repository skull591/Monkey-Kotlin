package edu.nju.ics.alex.combodroid.monkey

import android.Manifest
import android.content.pm.*
import android.os.Build
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import edu.nju.ics.alex.combodroid.utils.Logger
import sun.rmi.runtime.Log
import java.lang.Exception
import kotlin.random.Random


/**
 * Utility class that encapsulates runtime permission related methods for monkey
 *
 */
class MonkeyPermissionUtil {

    /**
     * actual list of packages to target, with invalid packages excluded, and
     * may optionally include system packages
     */
    private var mTargetedPackages: List<String> = listOf()
    /** if we should target system packages regardless if they are listed  */
    private var mTargetSystemPackages: Boolean = false
    private val mPm: IPackageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))

    /** keep track of runtime permissions requested for each package targeted  */
    private var mPermissionMap: MutableMap<String, List<PermissionInfo>> = mutableMapOf()

    fun setTargetSystemPackages(targetSystemPackages: Boolean) {
        mTargetSystemPackages = targetSystemPackages
    }

    /**
     * Decide if a package should be targeted by permission monkey
     *
     * @param info
     * @return
     */
    private fun shouldTargetPackage(info: PackageInfo): Boolean {
        // target if permitted by white listing / black listing rules
        if (MonkeyUtils.sFilter.checkEnteringPackage(info.packageName)) {
            return true
        }
        return (mTargetSystemPackages
            // not explicitly black listed
            && !MonkeyUtils.sFilter.isPackageInvalid(info.packageName)
            // is a system app
            && info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        )
    }

    @Throws(RemoteException::class)
    private fun shouldTargetPermission(pkg: String, pi: PermissionInfo): Boolean {
        val flags = mPm.getPermissionFlags(pi.name, pkg, UserHandle.myUserId())
        val fixedPermFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED or PackageManager.FLAG_PERMISSION_POLICY_FIXED
        return (pi.group != null && pi.protectionLevel == PermissionInfo.PROTECTION_DANGEROUS
                && flags and fixedPermFlags == 0 && isModernPermissionGroup(pi.group))
    }

    fun populatePermissionsMapping(): Boolean {
        println("populate permissions!")
        mPermissionMap = HashMap()
        try {
            val pkgInfos = mPm.getInstalledPackages(PackageManager.GET_PERMISSIONS, UserHandle.myUserId())
                .list
            for (o in pkgInfos) {
                val info = o as PackageInfo
                if (!shouldTargetPackage(info)) {
                    println("should not target package")
                    continue
                }
                println("should target permission ${info.packageName}")
                val permissions = ArrayList<PermissionInfo>()
                if (info.applicationInfo.targetSdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    // skip apps targetting lower API level
                    println("api level too low ${info.applicationInfo.targetSdkVersion}")
                    continue
                }
                if (info.requestedPermissions == null) {
                    println("request no permissions ${info.packageName}")
                    continue
                }
                for (perm in info.requestedPermissions) {
                     val pi = mPm.getPermissionInfo(perm, 0);
                    /** Here ape uses its own adapter*/
                    //TODO: change it back
                    //val pi = ApeAPIAdapter.getPermissionInfo(mPm, perm, 0)
                    if (pi != null && shouldTargetPermission(info.packageName, pi!!)) {
                        Logger.iLPrintln(pi!!.toString())
                        permissions.add(pi)
                    }
                }
                if (!permissions.isEmpty()) {
                    mPermissionMap!![info.packageName] = permissions
                }
            }
        } catch (re: RemoteException) {
            System.err.println("** Failed talking with package manager!")
            return false
        }

        if (!mPermissionMap!!.isEmpty()) {
            mTargetedPackages = ArrayList(mPermissionMap!!.keys)
        }
        return true
    }

    fun dump() {
        Logger.lPrintln("// Targeted packages and permissions:")
        for ((key, value) in mPermissionMap!!) {
            Logger.lPrintln(String.format("//  + Using %s", key))
            for (pi in value) {
                var name: String? = pi.name
                if (name != null) {
                    if (name.startsWith(PERMISSION_PREFIX)) {
                        name = name.substring(PERMISSION_PREFIX.length)
                    }
                }
                var group: String? = pi.group
                if (group != null) {
                    if (group.startsWith(PERMISSION_GROUP_PREFIX)) {
                        group = group.substring(PERMISSION_GROUP_PREFIX.length)
                    }
                }
                Logger.lPrintln(String.format("//    Permission: %s [%s]", name, group))
            }
        }
    }

    fun generateRandomPermissionEvent(random: Random): MonkeyPermissionEvent? {
        try {
            val pkg = mTargetedPackages[random.nextInt(mTargetedPackages.size)]
            val infos = mPermissionMap[pkg]
            return MonkeyPermissionEvent(pkg, infos!!.get(random.nextInt(infos.size)))
        } catch (e: Exception) {
            System.err.println("failed to generate permission event $e")
            return null
        }
    }

    companion object {

        private val PERMISSION_PREFIX = "android.permission."
        private val PERMISSION_GROUP_PREFIX = "android.permission-group."

        // from com.android.packageinstaller.permission.utils
        private val MODERN_PERMISSION_GROUPS = arrayOf(
            Manifest.permission_group.CALENDAR,
            Manifest.permission_group.CAMERA,
            Manifest.permission_group.CONTACTS,
            Manifest.permission_group.LOCATION,
            Manifest.permission_group.SENSORS,
            Manifest.permission_group.SMS,
            Manifest.permission_group.PHONE,
            Manifest.permission_group.MICROPHONE,
            Manifest.permission_group.STORAGE
        )

        // from com.android.packageinstaller.permission.utils
        private fun isModernPermissionGroup(name: String): Boolean {
            for (modernGroup in MODERN_PERMISSION_GROUPS) {
                if (modernGroup == name) {
                    return true
                }
            }
            return false
        }
    }
}
