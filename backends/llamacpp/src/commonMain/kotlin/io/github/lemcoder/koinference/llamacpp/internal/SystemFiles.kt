package io.github.lemcoder.koinference.llamacpp.internal

/**
 * The small text files Linux describes its CPUs through.
 *
 * An interface so the policy above it is testable against topologies nobody here owns — a
 * three-cluster SoC, a machine whose cores all clock the same, an app confined to the little
 * cluster. Returns null for a file that does not exist, which is the normal case off Linux.
 */
internal interface SystemFiles {
    fun read(path: String): String?
}
