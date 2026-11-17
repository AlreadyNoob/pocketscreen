package com.hiderlab.vd

import android.os.Process

/**
 * One copy of an app: its package name and the Android user that copy lives in. An original and its
 * clone share the package name and differ only by [user].
 */
data class AppRef(val pkg: String, val user: Int = OWNER) {
    val isClone: Boolean get() = user != OWNER

    /**
     * Name for saved settings, maps and notification ids. For the owner's copy it is the bare package
     * name, so everything saved before clones existed keeps working. Never put a key into a shell command.
     */
    val key: String get() = if (user == OWNER) pkg else "$pkg@$user"

    companion object {
        /** The user this app runs as. Don't use this inside [VdService]: it runs as the shell uid. */
        val OWNER: Int = Process.myUid() / 100000

        fun fromKey(key: String): AppRef {
            val at = key.lastIndexOf('@')
            if (at > 0) key.substring(at + 1).toIntOrNull()?.let { return AppRef(key.substring(0, at), it) }
            return AppRef(key, OWNER)
        }
    }
}
