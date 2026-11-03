package com.mdeo.script.stdlib.impl.collections

/**
 * Implementation of [ScriptMap] backed by a LinkedHashMap.
 * A mutable map that provides key-value pair access with modification capabilities.
 *
 * @param K the type of keys in this map
 * @param V the type of values in this map
 */
class MapImpl<K, V> : ScriptMap<K, V>, HeapMap {

    private val backing: LinkedHashMap<K, V>

    /**
     * Mutation counter, see [HeapMap.heapVersion].
     */
    private var version: Long = 0

    override val heapVersion: Long get() = version

    override fun heapEntries(): List<Pair<Any?, Any?>> = backing.entries.map { it.key to it.value }

    @Suppress("UNCHECKED_CAST")
    override fun heapReplace(entries: List<Pair<Any?, Any?>>) {
        version++
        backing.clear()
        for ((key, value) in entries) backing[key as K] = value as V
    }

    /**
     * Creates an empty map.
     */
    constructor() {
        backing = LinkedHashMap()
    }

    /**
     * Creates a map containing all entries from the given map.
     *
     * @param entries the entries to add to the map
     */
    constructor(entries: Map<K, V>) {
        backing = LinkedHashMap(entries)
    }

    override fun size(): Int = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun containsKey(key: K): Boolean = backing.containsKey(key)

    override fun containsValue(value: V): Boolean = backing.containsValue(value)

    override fun get(key: K): V? = backing[key]

    override fun keySet(): ReadonlySet<K> = SetImpl(backing.keys)

    override fun values(): ReadonlyBag<V> = BagImpl(backing.values)

    override fun clear() {
        version++
        backing.clear()
    }

    override fun put(key: K, value: V) {
        version++
        backing[key] = value
    }

    override fun putAll(map: ReadonlyMap<K, V>) {
        version++
        for (key in map.keySet()) {
            @Suppress("UNCHECKED_CAST")
            backing[key] = map.get(key) as V
        }
    }

    override fun remove(key: K): V? {
        version++
        return backing.remove(key)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReadonlyMap<*, *>) return false
        if (size() != other.size()) return false
        for (key in backing.keys) {
            @Suppress("UNCHECKED_CAST")
            if (backing[key] != (other as ReadonlyMap<K, V>).get(key)) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int = backing.hashCode()

    override fun toString(): String = backing.toString()

    companion object {
        /**
         * Creates a map from the given pairs.
         *
         * @param pairs the key-value pairs to add to the map
         * @return a new map containing the pairs
         */
        @JvmStatic
        fun <K, V> of(vararg pairs: Pair<K, V>): ScriptMap<K, V> {
            val map = MapImpl<K, V>()
            for ((key, value) in pairs) {
                map.put(key, value)
            }
            return map
        }

        /**
         * Creates an empty map.
         *
         * @return a new empty map
         */
        @JvmStatic
        fun <K, V> empty(): ScriptMap<K, V> = MapImpl()
    }
}
