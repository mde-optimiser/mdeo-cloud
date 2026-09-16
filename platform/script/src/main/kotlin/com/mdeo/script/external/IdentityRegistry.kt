package com.mdeo.script.external

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Gives every collection one id for the whole session, by identity, without keeping it alive.
 *
 * Identity is what makes aliasing and cycles survive a call: the same list passed twice is sent
 * once and referenced twice, and a list that contains itself is a reference to its own id. The
 * references are weak, so a collection the script dropped can be collected; its id is then
 * reported through [drainReleased] so the service can forget it too.
 */
internal class IdentityRegistry {

    private class Entry(referent: Any, val id: Long, queue: ReferenceQueue<Any>) :
        WeakReference<Any>(referent, queue)

    private val queue = ReferenceQueue<Any>()
    private val byHash = HashMap<Int, MutableList<Entry>>()
    private val byId = HashMap<Long, Entry>()
    private var nextId = 1L

    /**
     * Looks up the id of [value], or null when it has none yet.
     */
    fun idOf(value: Any): Long? {
        val bucket = byHash[System.identityHashCode(value)] ?: return null
        return bucket.firstOrNull { it.get() === value }?.id
    }

    /**
     * Returns the id of [value], assigning a fresh positive one when it has none.
     */
    fun idFor(value: Any): Long = idOf(value) ?: register(value, nextId++)

    /**
     * Records [value] under an id the service chose.
     */
    fun register(value: Any, id: Long): Long {
        val entry = Entry(value, id, queue)
        byHash.getOrPut(System.identityHashCode(value)) { ArrayList(1) }.add(entry)
        byId[id] = entry
        return id
    }

    /**
     * Looks up the collection registered under [id], if it is still alive.
     */
    fun objectOf(id: Long): Any? = byId[id]?.get()

    /**
     * Returns the ids of collections collected since the last call, and forgets them.
     */
    fun drainReleased(): List<Long> {
        val released = ArrayList<Long>()
        while (true) {
            val entry = queue.poll() as Entry? ?: break
            released += entry.id
            byId.remove(entry.id)
            byHash.values.forEach { bucket -> bucket.remove(entry) }
        }
        byHash.values.removeIf { it.isEmpty() }
        return released
    }
}
