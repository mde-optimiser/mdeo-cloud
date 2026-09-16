package com.mdeo.script.external

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * The handles a script holds to state on a service, by the id the service keeps it under.
 *
 * A handle is created once per id, so the same state is the same object in the script. Once the
 * script no longer references a handle and it is collected, its id is reported through
 * [drainReleased] so the service can drop the state.
 */
internal class HandleRegistry {

    private class HandleReference(handle: Any, queue: ReferenceQueue<Any>, val id: Long) :
        WeakReference<Any>(handle, queue)

    private val queue = ReferenceQueue<Any>()
    private val handles = HashMap<Long, HandleReference>()

    /**
     * Returns the handle for an id, creating it if the script holds none.
     *
     * @param id The id the service keeps the state under
     * @param create Creates the handle object
     * @return The handle
     */
    fun handleFor(id: Long, create: () -> Any): Any {
        handles[id]?.get()?.let { return it }
        val handle = create()
        handles[id] = HandleReference(handle, queue, id)
        return handle
    }

    /**
     * Returns the ids of handles the script no longer holds, and forgets them.
     *
     * @return The released ids
     */
    fun drainReleased(): List<Long> {
        val released = mutableListOf<Long>()
        while (true) {
            val reference = queue.poll() as? HandleReference ?: break
            // A handle collected after a new one was created for the same id is not a release.
            if (handles[reference.id] === reference) {
                handles.remove(reference.id)
                released += reference.id
            }
        }
        return released
    }
}
