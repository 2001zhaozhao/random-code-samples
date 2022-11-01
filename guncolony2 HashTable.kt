@file:Suppress("unused", "DuplicatedCode", "UNCHECKED_CAST", "ReplaceManualRangeWithIndicesCalls",
    "EqualsOrHashCode")
@file:OptIn(DocDoNotUse::class, DocDoNotUse::class)

package com.guncolony.util

import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

/*
This file contains various custom hash table types used in GC.
 */

/**
 * A resizing hash table with UShort keys and Object values, which uses open addressing with linear probing with a
 * maximum load factor of 5/8, minimum load factor of 1/8, and initial/minimum capacity of 8.
 * It is designed primarily for memory efficiency and speed. Initially, this hash table will not allocate arrays, and
 * they will be allocated only when the first element is being inserted.
 *
 * The 65535 key is not supported by this hash table, while all of 0..65534 are supported.
 * Using a nullable type as the value type is supported.
 * The hash used for the key is just the key itself, so the keys should be some kind of ID with no inherent meaning to
 * help avoid clustering.
 *
 * The unsupported key is 65535 and not 0 because using the key 0 is important for Minecraft compatibility reasons, e.g.
 * the air block always has an ID of 0 in Minecraft.
 *
 * This hash table is not thread-safe and does not properly implement hashCode(), though it does implement equals().
 */
@OptIn(ExperimentalUnsignedTypes::class)
open class UShortObjectHashTable<T> {
    companion object {
        /**
         * The special empty key of the UShortHashTable, with a value of 65535u. Do not use it as a key!
         */
        val EMPTY_KEY: UShort = 65535u
        private const val MIN_CAPACITY = 8
        private const val MAX_LOAD_FACTOR = 0.625
        private const val MIN_LOAD_FACTOR = 0.125 // Should be a lot less than half of the MAX_LOAD_FACTOR
    }

    /**
     * The current number of elements in the hash table.
     */
    var size: UShort = 0u
    private set

    /**
     * The current number of elements in the hash table, converted to an Int.
     */
    val sizeInt: Int inline get() = size.toInt()

    /** The array of keys in the hash table. A value of EMPTY_KEY means that the slot is empty. */
    @DocDoNotUse
    var keys: UShortArray? = null

    /** The array of values in the hash table. */
    @DocDoNotUse @JvmField
    var values: Array<T?>? = null

    /** Gets the hash bucket used for the key, calculated using the current hash table array size. */
    private fun hashBucket(key: UShort): Int = key.toInt() % keys!!.size

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    operator fun set(key: UShort, value: T): Boolean {
        if(keys == null) {
            keys = UShortArray(MIN_CAPACITY){EMPTY_KEY}
            values = arrayOfNulls<Any?>(MIN_CAPACITY) as Array<T?>
        }
        val keys = keys!!
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            // If the bucket is unoccupied, add this key into it
            if(keyAtBucket == EMPTY_KEY) {
                keys[bucket] = key
                values[bucket] = value
                size++
                // Grow the map if we are above the maximum load factor
                if(size.toDouble() > MAX_LOAD_FACTOR * keys.size) {
                    resize(keys.size * 2)
                }
                return true
            }
            // If the key is already in the map, change its value and return
            if(keyAtBucket == key) {
                values[bucket] = value
                return false
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun put(key: UShort, value: T) = set(key, value)

    /**
     * Gets the value associated with the given key, or null if not found.
     *
     * Note that a null value will also be returned if the value type is nullable and the null value was explicitly
     * set in the map. In this case, the [contains] function will still return true.
     *
     * @param key The key, must not be 65535
     * @return The value associated with the key
     */
    operator fun get(key: UShort): T? {
        val keys = keys?:return null
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return null
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Returns whether this hash table contains the given key.
     *
     * @param key The key, must not be 65535
     * @return Whether the key exists in the hash table
     */
    fun contains(key: UShort): Boolean {
        val keys = keys?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            if(keyAtBucket == key) return true
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes the given key from this hash table, if it exists.
     *
     * @param key The key, must not be 65535
     * @return True if the key exists and was removed, false if the key did not exist and nothing was done
     */
    fun remove(key: UShort): Boolean {
        val keys = keys?:return false
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            // If we see the key, remove it and compress the array
            if(keyAtBucket == key) {
                size--
                // If the hash table is now empty, we can just remove the key and value arrays
                if(size.toInt() == 0) {
                    this.keys = null
                    this.values = null
                    return true
                }
                keys[bucket] = EMPTY_KEY
                values[bucket] = null
                // Shrink the map if we are below the minimum load factor, otherwise compress
                if(keys.size >= MIN_CAPACITY * 2 && size.toDouble() < MIN_LOAD_FACTOR * keys.size)
                    resize(keys.size / 2)
                else
                    compress(bucket)
                return true
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes all given keys from this hash table. Keys that do not exist in the hash table will be ignored.
     *
     * @param keys An array containing the given keys
     * @return The number of keys actually removed
     */
    fun removeAll(keys: UShortArray): Int = keys.count {remove(it)}

    /**
     * Called just after deleting an entry from a bucket. Repeatedly checks whether the next buckets can be moved into
     * the free slot, in order to compress the values and make sure that all remaining keys remain reachable from
     * their own buckets.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun compress(startingBucket: Int) {
        val keys = keys!!
        val values = values!!

        var freeBucket = startingBucket
        var bucket = (freeBucket + 1) % keys.size
        while(true) {
            val keyAtBucket = keys[bucket]
            // Return when reaching a free slot
            if(keyAtBucket == EMPTY_KEY) return
            val bucketOfKey = hashBucket(keyAtBucket)
            // Check whether this removal will create a free bucket between the natural bucket and current bucket of
            // this key. Test from https://stackoverflow.com/questions/9127207
            if((bucket > freeBucket && (bucketOfKey <= freeBucket || bucketOfKey > bucket)) ||
                (bucket < freeBucket && (bucketOfKey <= freeBucket && bucketOfKey > bucket))) {
                // Move current element
                keys[freeBucket] = keyAtBucket
                values[freeBucket] = values[bucket]
                // Mark current slot as free
                keys[bucket] = EMPTY_KEY
                values[bucket] = null
                freeBucket = bucket
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /** Resizes this hash table to a new size. The size must be enough to fit all existing data in the map. */
    private fun resize(newSize: Int) {
        val currentKeys = this.keys!!
        val currentValues = this.values!!

        val keys = UShortArray(newSize){EMPTY_KEY}
        this.keys = keys
        val values = arrayOfNulls<Any?>(newSize) as Array<T?>
        this.values = values

        // Copy data to the new arrays
        for(currentBucket in 0 until currentKeys.size) {
            val key = currentKeys[currentBucket]
            if(key == EMPTY_KEY) continue
            val value = currentValues[currentBucket] as T

            // The below is a copy of the "put" function, but without modifying size or checking for another resize
            var bucket = hashBucket(key)
            while(true) {
                val keyAtBucket = keys[bucket]
                // If the bucket is unoccupied, add this key into it
                if(keyAtBucket == EMPTY_KEY) {
                    keys[bucket] = key
                    values[bucket] = value
                    break
                }
                // If the key is already in the map, change its value and return
                if(keyAtBucket == key) {
                    values[bucket] = value
                    break
                }
                bucket = (bucket + 1) % keys.size
            }
        }
    }

    /**
     * Performs the given action for each entry of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key, value pair.
     */
    inline fun forEach(action: (UShort, T) -> Unit) {
        val keys = keys?:return
        val values = values!!
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            val value = values[bucket] as T
            action(key, value)
        }
    }

    /**
     * Performs the given action for each key of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key in the hash table.
     */
    inline fun forEachKey(action: (UShort) -> Unit) {
        val keys = keys?:return
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            action(key)
        }
    }

    /**
     * Performs the given action for each value of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a value in the hash table.
     */
    inline fun forEachValue(action: (T) -> Unit) {
        val keys = keys?:return
        val values = values!!
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            val value = values[bucket] as T
            action(value)
        }
    }

    override fun equals(other: Any?): Boolean {
        if(other == null || other::class != this::class) return false
        other as UShortObjectHashTable<*>
        if(size != other.size) return false
        forEach{key, value ->
            if(!other.contains(key) || other[key] != value) return false
        }
        return true
    }
}

/**
 * An interface version of [UShortObjectHashTable].
 *
 * This allows a form of multiple inheritance where the object embeds the data of this hash table within itself
 * for memory savings, instead of using a reference to a standalone hash table.
 */
@OptIn(ExperimentalUnsignedTypes::class)
interface IUShortObjectHashTable<T> {
    companion object {
        /**
         * The special empty key of the UShortHashTable, with a value of 65535u. Do not use it as a key!
         */
        val EMPTY_KEY: UShort = 65535u
        private const val MIN_CAPACITY = 8
        private const val MAX_LOAD_FACTOR = 0.625
        private const val MIN_LOAD_FACTOR = 0.125 // Should be a lot less than half of the MAX_LOAD_FACTOR
    }

    /**
     * The current number of elements in the hash table.
     *
     * Must be implemented with the zero value, i.e. `0u.toUShort()`.
     */
    @DocStrictImplementation
    var sizeUSOHT: UShort

    /**
     * The current number of elements in the hash table, converted to an Int.
     */
    val sizeIntUSOHT: Int get() = sizeUSOHT.toInt()

    /** The array of keys in the hash table. A value of EMPTY_KEY means that the slot is empty.
     *
     * Must be implemented to start with a null value. */
    @DocDoNotUse @DocStrictImplementation
    var keysUSOHT: UShortArray?

    /** The array of values in the hash table.
     *
     * Must be implemented to start with a null value. */
    @DocDoNotUse @DocStrictImplementation
    var valuesUSOHT: Array<T?>?

    /** Gets the hash bucket used for the key, calculated using the current hash table array size. */
    private fun hashBucket(key: UShort): Int = key.toInt() % keysUSOHT!!.size

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun setUSOHT(key: UShort, value: T): Boolean {
        if(keysUSOHT == null) {
            keysUSOHT = UShortArray(MIN_CAPACITY){EMPTY_KEY}
            valuesUSOHT = arrayOfNulls<Any?>(MIN_CAPACITY) as Array<T?>
        }
        val keys = keysUSOHT!!
        val values = valuesUSOHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            // If the bucket is unoccupied, add this key into it
            if(keyAtBucket == EMPTY_KEY) {
                keys[bucket] = key
                values[bucket] = value
                sizeUSOHT++
                // Grow the map if we are above the maximum load factor
                if(sizeUSOHT.toDouble() > MAX_LOAD_FACTOR * keys.size) {
                    resize(keys.size * 2)
                }
                return true
            }
            // If the key is already in the map, change its value and return
            if(keyAtBucket == key) {
                values[bucket] = value
                return false
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun putUSOHT(key: UShort, value: T) = setUSOHT(key, value)

    /**
     * Gets the value associated with the given key, or null if not found.
     *
     * Note that a null value will also be returned if the value type is nullable and the null value was explicitly
     * set in the map. In this case, the [containsUSOHT] function will still return true.
     *
     * @param key The key, must not be 65535
     * @return The value associated with the key
     */
    fun getUSOHT(key: UShort): T? {
        val keys = keysUSOHT?:return null
        val values = valuesUSOHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return null
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Returns whether this hash table contains the given key.
     *
     * @param key The key, must not be 65535
     * @return Whether the key exists in the hash table
     */
    fun containsUSOHT(key: UShort): Boolean {
        val keys = keysUSOHT?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            if(keyAtBucket == key) return true
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes the given key from this hash table, if it exists.
     *
     * @param key The key, must not be 65535
     * @return True if the key exists and was removed, false if the key did not exist and nothing was done
     */
    fun removeUSOHT(key: UShort): Boolean {
        val keys = keysUSOHT?:return false
        val values = valuesUSOHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            // If we see the key, remove it and compress the array
            if(keyAtBucket == key) {
                sizeUSOHT--
                // If the hash table is now empty, we can just remove the key and value arrays
                if(sizeUSOHT.toInt() == 0) {
                    this.keysUSOHT = null
                    this.valuesUSOHT = null
                    return true
                }
                keys[bucket] = EMPTY_KEY
                values[bucket] = null
                // Shrink the map if we are below the minimum load factor, otherwise compress
                if(keys.size >= MIN_CAPACITY * 2 && sizeUSOHT.toDouble() < MIN_LOAD_FACTOR * keys.size)
                    resize(keys.size / 2)
                else
                    compress(bucket)
                return true
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes all given keys from this hash table. Keys that do not exist in the hash table will be ignored.
     *
     * @param keys An array containing the given keys
     * @return The number of keys actually removed
     */
    fun removeAllUSOHT(keys: UShortArray): Int = keys.count {removeUSOHT(it)}

    /**
     * Called just after deleting an entry from a bucket. Repeatedly checks whether the next buckets can be moved into
     * the free slot, in order to compress the values and make sure that all remaining keys remain reachable from
     * their own buckets.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun compress(startingBucket: Int) {
        val keys = keysUSOHT!!
        val values = valuesUSOHT!!

        var freeBucket = startingBucket
        var bucket = (freeBucket + 1) % keys.size
        while(true) {
            val keyAtBucket = keys[bucket]
            // Return when reaching a free slot
            if(keyAtBucket == EMPTY_KEY) return
            val bucketOfKey = hashBucket(keyAtBucket)
            // Check whether this removal will create a free bucket between the natural bucket and current bucket of
            // this key. Test from https://stackoverflow.com/questions/9127207
            if((bucket > freeBucket && (bucketOfKey <= freeBucket || bucketOfKey > bucket)) ||
                (bucket < freeBucket && (bucketOfKey <= freeBucket && bucketOfKey > bucket))) {
                // Move current element
                keys[freeBucket] = keyAtBucket
                values[freeBucket] = values[bucket]
                // Mark current slot as free
                keys[bucket] = EMPTY_KEY
                values[bucket] = null
                freeBucket = bucket
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /** Resizes this hash table to a new size. The size must be enough to fit all existing data in the map. */
    private fun resize(newSize: Int) {
        println("resizing to $newSize")
        val currentKeys = this.keysUSOHT!!
        val currentValues = this.valuesUSOHT!!

        val keys = UShortArray(newSize){EMPTY_KEY}
        this.keysUSOHT = keys
        val values = arrayOfNulls<Any?>(newSize) as Array<T?>
        this.valuesUSOHT = values

        // Copy data to the new arrays
        for(currentBucket in 0 until currentKeys.size) {
            val key = currentKeys[currentBucket]
            if(key == EMPTY_KEY) continue
            val value = currentValues[currentBucket] as T

            // The below is a copy of the "put" function, but without modifying size or checking for another resize
            var bucket = hashBucket(key)
            while(true) {
                val keyAtBucket = keys[bucket]
                // If the bucket is unoccupied, add this key into it
                if(keyAtBucket == EMPTY_KEY) {
                    keys[bucket] = key
                    values[bucket] = value
                    break
                }
                // If the key is already in the map, change its value and return
                if(keyAtBucket == key) {
                    values[bucket] = value
                    break
                }
                bucket = (bucket + 1) % keys.size
            }
        }
    }

    /**
     * Returns whether this hash table's contents are the same as the other hash table, i.e. both hash tables
     * have the same size and the same set of keys, and each key has the same value.
     */
    fun equalsUSOHT(other: IUShortObjectHashTable<*>): Boolean {
        if(sizeUSOHT != other.sizeUSOHT) return false
        forEachUSOHT{key, value ->
            if(!other.containsUSOHT(key) || other.getUSOHT(key) != value) return false
        }
        return true
    }
}

/**
 * A resizing hash table with UShort keys and Long values, which uses open addressing with linear probing with a
 * maximum load factor of 5/8, minimum load factor of 1/8, and initial/minimum capacity of 8.
 * It is designed primarily for memory efficiency and speed. Initially, this hash table will not allocate arrays, and
 * they will be allocated only when the first element is being inserted.
 *
 * The 65535 key is not supported by this hash table, while all of 0..65534 are supported.
 * The hash used for the key is just the key itself, so the keys should be some kind of ID with no inherent meaning to
 * help avoid clustering.
 *
 * The unsupported key is 65535 and not 0 because using the key 0 is important for Minecraft compatibility reasons, e.g.
 * the air block always has an ID of 0 in Minecraft.
 *
 * This hash table is not thread-safe and does not properly implement hashCode().
 */
@OptIn(ExperimentalUnsignedTypes::class)
open class UShortLongHashTable {
    companion object {
        /**
         * The special empty key of the UShortHashTable, with a value of 65535u. Do not use it as a key!
         */
        val EMPTY_KEY: UShort = 65535u
        private const val MIN_CAPACITY = 8
        private const val MAX_LOAD_FACTOR = 0.625
        private const val MIN_LOAD_FACTOR = 0.125 // Should be a lot less than half of the MAX_LOAD_FACTOR
    }

    /**
     * The current number of elements in the hash table.
     */
    var size: UShort = 0u
        private set

    /**
     * The current number of elements in the hash table, converted to an Int.
     */
    val sizeInt: Int inline get() = size.toInt()

    /** The array of keys in the hash table. A value of EMPTY_KEY means that the slot is empty. */
    @DocDoNotUse
    var keys: UShortArray? = null

    /** The array of values in the hash table. */
    @DocDoNotUse @JvmField
    var values: LongArray? = null

    /** Gets the hash bucket used for the key, calculated using the current hash table array size. */
    private fun hashBucket(key: UShort): Int = key.toInt() % keys!!.size

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    operator fun set(key: UShort, value: Long): Boolean {
        if(keys == null) {
            keys = UShortArray(MIN_CAPACITY){EMPTY_KEY}
            values = LongArray(MIN_CAPACITY)
        }
        val keys = keys!!
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            // If the bucket is unoccupied, add this key into it
            if(keyAtBucket == EMPTY_KEY) {
                keys[bucket] = key
                values[bucket] = value
                size++
                // Grow the map if we are above the maximum load factor
                if(size.toDouble() > MAX_LOAD_FACTOR * keys.size) {
                    resize(keys.size * 2)
                }
                return true
            }
            // If the key is already in the map, change its value and return
            if(keyAtBucket == key) {
                values[bucket] = value
                return false
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun put(key: UShort, value: Long) = set(key, value)

    /**
     * Gets the value associated with the given key, or null if not found.
     *
     * @param key The key, must not be 65535
     * @return The value associated with the key
     */
    fun getOrNull(key: UShort): Long? {
        val keys = keys?:return null
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return null
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Gets the value associated with the given key, or a default value if not found.
     *
     * @param key The key, must not be 65535
     * @param default The default value returned if the key is not found. The "default" default value is zero.
     * @return The value associated with the key
     */
    operator fun get(key: UShort, default: Long = 0): Long {
        val keys = keys?:return default
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return default
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Returns whether this hash table contains the given key.
     *
     * @param key The key, must not be 65535
     * @return Whether the key exists in the hash table
     */
    fun contains(key: UShort): Boolean {
        val keys = keys?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            if(keyAtBucket == key) return true
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes the given key from this hash table, if it exists.
     *
     * @param key The key, must not be 65535
     * @return True if the key exists and was removed, false if the key did not exist and nothing was done
     */
    fun remove(key: UShort): Boolean {
        val keys = keys?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            // If we see the key, remove it and compress the array
            if(keyAtBucket == key) {
                size--
                // If the hash table is now empty, we can just remove the key and value arrays
                if(size.toInt() == 0) {
                    this.keys = null
                    this.values = null
                    return true
                }
                keys[bucket] = EMPTY_KEY
                // Shrink the map if we are below the minimum load factor, otherwise compress
                if(keys.size >= MIN_CAPACITY * 2 && size.toDouble() < MIN_LOAD_FACTOR * keys.size)
                    resize(keys.size / 2)
                else
                    compress(bucket)
                return true
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes all given keys from this hash table. Keys that do not exist in the hash table will be ignored.
     *
     * @param keys An array containing the given keys
     * @return The number of keys actually removed
     */
    fun removeAll(keys: UShortArray): Int = keys.count {remove(it)}

    /**
     * Called just after deleting an entry from a bucket. Repeatedly checks whether the next buckets can be moved into
     * the free slot, in order to compress the values and make sure that all remaining keys remain reachable from
     * their own buckets.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun compress(startingBucket: Int) {
        val keys = keys!!
        val values = values!!

        var freeBucket = startingBucket
        var bucket = (freeBucket + 1) % keys.size
        while(true) {
            val keyAtBucket = keys[bucket]
            // Return when reaching a free slot
            if(keyAtBucket == EMPTY_KEY) return
            val bucketOfKey = hashBucket(keyAtBucket)
            // Check whether this removal will create a free bucket between the natural bucket and current bucket of
            // this key. Test from https://stackoverflow.com/questions/9127207
            if((bucket > freeBucket && (bucketOfKey <= freeBucket || bucketOfKey > bucket)) ||
                (bucket < freeBucket && (bucketOfKey <= freeBucket && bucketOfKey > bucket))) {
                // Move current element
                keys[freeBucket] = keyAtBucket
                values[freeBucket] = values[bucket]
                // Mark current slot as free
                keys[bucket] = EMPTY_KEY
                freeBucket = bucket
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /** Resizes this hash table to a new size. The size must be enough to fit all existing data in the map. */
    private fun resize(newSize: Int) {
        val currentKeys = this.keys!!
        val currentValues = this.values!!

        val keys = UShortArray(newSize){EMPTY_KEY}
        this.keys = keys
        val values = LongArray(newSize)
        this.values = values

        // Copy data to the new arrays
        for(currentBucket in 0 until currentKeys.size) {
            val key = currentKeys[currentBucket]
            if(key == EMPTY_KEY) continue
            val value = currentValues[currentBucket]

            // The below is a copy of the "put" function, but without modifying size or checking for another resize
            var bucket = hashBucket(key)
            while(true) {
                val keyAtBucket = keys[bucket]
                // If the bucket is unoccupied, add this key into it
                if(keyAtBucket == EMPTY_KEY) {
                    keys[bucket] = key
                    values[bucket] = value
                    break
                }
                // If the key is already in the map, change its value and return
                if(keyAtBucket == key) {
                    values[bucket] = value
                    break
                }
                bucket = (bucket + 1) % keys.size
            }
        }
    }

    /**
     * Performs the given action for each entry of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key, value pair.
     */
    inline fun forEach(action: (UShort, Long) -> Unit) {
        val keys = keys?:return
        val values = values!!
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            val value = values[bucket]
            action(key, value)
        }
    }

    /**
     * Performs the given action for each key of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key in the hash table.
     */
    inline fun forEachKey(action: (UShort) -> Unit) {
        val keys = keys?:return
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            action(key)
        }
    }

    /**
     * Performs the given action for each value of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a value in the hash table.
     */
    inline fun forEachValue(action: (Long) -> Unit) {
        val keys = keys?:return
        val values = values!!
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            val value = values[bucket]
            action(value)
        }
    }

    override fun equals(other: Any?): Boolean {
        if(other == null || other::class != this::class) return false
        other as UShortLongHashTable
        if(size != other.size) return false
        forEach{key, value ->
            if(!other.contains(key) || other[key] != value) return false
        }
        return true
    }
}

/**
 * An interface version of [UShortObjectHashTable].
 *
 * This allows a form of multiple inheritance where the object embeds the data of this hash table within itself
 * for memory savings, instead of using a reference to a standalone hash table.
 */
@OptIn(ExperimentalUnsignedTypes::class)
interface IUShortLongHashTable {
    companion object {
        /**
         * The special empty key of the UShortHashTable, with a value of 65535u. Do not use it as a key!
         */
        val EMPTY_KEY: UShort = 65535u
        private const val MIN_CAPACITY = 8
        private const val MAX_LOAD_FACTOR = 0.625
        private const val MIN_LOAD_FACTOR = 0.125 // Should be a lot less than half of the MAX_LOAD_FACTOR
    }

    /**
     * The current number of elements in the hash table.
     *
     * Must be implemented with the zero value, i.e. `0u.toUShort()`.
     */
    @DocStrictImplementation
    var sizeUSLHT: UShort

    /**
     * The current number of elements in the hash table, converted to an Int.
     */
    val sizeIntUSLHT: Int get() = sizeUSLHT.toInt()

    /** The array of keys in the hash table. A value of EMPTY_KEY means that the slot is empty.
     *
     * Must be implemented to start with a null value. */
    @DocDoNotUse @DocStrictImplementation
    var keysUSLHT: UShortArray?

    /** The array of values in the hash table.
     *
     * Must be implemented to start with a null value. */
    @DocDoNotUse @DocStrictImplementation
    var valuesUSLHT: LongArray?

    /** Gets the hash bucket used for the key, calculated using the current hash table array size. */
    private fun hashBucket(key: UShort): Int = key.toInt() % keysUSLHT!!.size

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun setUSLHT(key: UShort, value: Long): Boolean {
        if(keysUSLHT == null) {
            keysUSLHT = UShortArray(MIN_CAPACITY){EMPTY_KEY}
            valuesUSLHT = LongArray(MIN_CAPACITY)
        }
        val keys = keysUSLHT!!
        val values = valuesUSLHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            // If the bucket is unoccupied, add this key into it
            if(keyAtBucket == EMPTY_KEY) {
                keys[bucket] = key
                values[bucket] = value
                sizeUSLHT++
                // Grow the map if we are above the maximum load factor
                if(sizeUSLHT.toDouble() > MAX_LOAD_FACTOR * keys.size) {
                    resize(keys.size * 2)
                }
                return true
            }
            // If the key is already in the map, change its value and return
            if(keyAtBucket == key) {
                values[bucket] = value
                return false
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun putUSLHT(key: UShort, value: Long) = setUSLHT(key, value)

    /**
     * Gets the value associated with the given key, or null if not found.
     *
     * @param key The key, must not be 65535
     * @return The value associated with the key
     */
    fun getOrNullUSLHT(key: UShort): Long? {
        val keys = keysUSLHT?:return null
        val values = valuesUSLHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return null
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Gets the value associated with the given key, or a default value if not found.
     *
     * @param key The key, must not be 65535
     * @param default The default value returned if the key is not found. The "default" default value is zero.
     * @return The value associated with the key
     */
    fun getUSLHT(key: UShort, default: Long = 0): Long {
        val keys = keysUSLHT?:return default
        val values = valuesUSLHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return default
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Returns whether this hash table contains the given key.
     *
     * @param key The key, must not be 65535
     * @return Whether the key exists in the hash table
     */
    fun containsUSLHT(key: UShort): Boolean {
        val keys = keysUSLHT?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            if(keyAtBucket == key) return true
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes the given key from this hash table, if it exists.
     *
     * @param key The key, must not be 65535
     * @return True if the key exists and was removed, false if the key did not exist and nothing was done
     */
    fun removeUSLHT(key: UShort): Boolean {
        val keys = keysUSLHT?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            // If we see the key, remove it and compress the array
            if(keyAtBucket == key) {
                sizeUSLHT--
                // If the hash table is now empty, we can just remove the key and value arrays
                if(sizeUSLHT.toInt() == 0) {
                    this.keysUSLHT = null
                    this.valuesUSLHT = null
                    return true
                }
                keys[bucket] = EMPTY_KEY
                // Shrink the map if we are below the minimum load factor, otherwise compress
                if(keys.size >= MIN_CAPACITY * 2 && sizeUSLHT.toDouble() < MIN_LOAD_FACTOR * keys.size)
                    resize(keys.size / 2)
                else
                    compress(bucket)
                return true
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes all given keys from this hash table. Keys that do not exist in the hash table will be ignored.
     *
     * @param keys An array containing the given keys
     * @return The number of keys actually removed
     */
    fun removeAllUSLHT(keys: UShortArray): Int = keys.count {removeUSLHT(it)}

    /**
     * Called just after deleting an entry from a bucket. Repeatedly checks whether the next buckets can be moved into
     * the free slot, in order to compress the values and make sure that all remaining keys remain reachable from
     * their own buckets.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun compress(startingBucket: Int) {
        val keys = keysUSLHT!!
        val values = valuesUSLHT!!

        var freeBucket = startingBucket
        var bucket = (freeBucket + 1) % keys.size
        while(true) {
            val keyAtBucket = keys[bucket]
            // Return when reaching a free slot
            if(keyAtBucket == EMPTY_KEY) return
            val bucketOfKey = hashBucket(keyAtBucket)
            // Check whether this removal will create a free bucket between the natural bucket and current bucket of
            // this key. Test from https://stackoverflow.com/questions/9127207
            if((bucket > freeBucket && (bucketOfKey <= freeBucket || bucketOfKey > bucket)) ||
                (bucket < freeBucket && (bucketOfKey <= freeBucket && bucketOfKey > bucket))) {
                // Move current element
                keys[freeBucket] = keyAtBucket
                values[freeBucket] = values[bucket]
                // Mark current slot as free
                keys[bucket] = EMPTY_KEY
                freeBucket = bucket
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /** Resizes this hash table to a new size. The size must be enough to fit all existing data in the map. */
    private fun resize(newSize: Int) {
        val currentKeys = this.keysUSLHT!!
        val currentValues = this.valuesUSLHT!!

        val keys = UShortArray(newSize){EMPTY_KEY}
        this.keysUSLHT = keys
        val values = LongArray(newSize)
        this.valuesUSLHT = values

        // Copy data to the new arrays
        for(currentBucket in 0 until currentKeys.size) {
            val key = currentKeys[currentBucket]
            if(key == EMPTY_KEY) continue
            val value = currentValues[currentBucket]

            // The below is a copy of the "put" function, but without modifying size or checking for another resize
            var bucket = hashBucket(key)
            while(true) {
                val keyAtBucket = keys[bucket]
                // If the bucket is unoccupied, add this key into it
                if(keyAtBucket == EMPTY_KEY) {
                    keys[bucket] = key
                    values[bucket] = value
                    break
                }
                // If the key is already in the map, change its value and return
                if(keyAtBucket == key) {
                    values[bucket] = value
                    break
                }
                bucket = (bucket + 1) % keys.size
            }
        }
    }

    /**
     * Returns whether this hash table's contents are the same as the other hash table, i.e. both hash tables
     * have the same size and the same set of keys, and each key has the same value.
     */
    fun equalsUSLHT(other: IUShortLongHashTable): Boolean {
        if(sizeUSLHT != other.sizeUSLHT) return false
        forEachUSLHT{key, value ->
            if(!other.containsUSLHT(key) || other.getUSLHT(key) != value) return false
        }
        return true
    }
}

/**
 * A resizing hash table with UShort keys and Int values, which uses open addressing with linear probing with a
 * maximum load factor of 5/8, minimum load factor of 1/8, and initial/minimum capacity of 8.
 * It is designed primarily for memory efficiency and speed. Initially, this hash table will not allocate arrays, and
 * they will be allocated only when the first element is being inserted.
 *
 * The 65535 key is not supported by this hash table, while all of 0..65534 are supported.
 * The hash used for the key is just the key itself, so the keys should be some kind of ID with no inherent meaning to
 * help avoid clustering.
 *
 * The unsupported key is 65535 and not 0 because using the key 0 is important for Minecraft compatibility reasons, e.g.
 * the air block always has an ID of 0 in Minecraft.
 *
 * This hash table is not thread-safe and does not properly implement hashCode().
 */
@OptIn(ExperimentalUnsignedTypes::class)
open class UShortIntHashTable {
    companion object {
        /**
         * The special empty key of the UShortHashTable, with a value of 65535u. Do not use it as a key!
         */
        val EMPTY_KEY: UShort = 65535u
        private const val MIN_CAPACITY = 8
        private const val MAX_LOAD_FACTOR = 0.625
        private const val MIN_LOAD_FACTOR = 0.125 // Should be a lot less than half of the MAX_LOAD_FACTOR
    }

    /**
     * The current number of elements in the hash table.
     */
    var size: UShort = 0u
        private set

    /**
     * The current number of elements in the hash table, converted to an Int.
     */
    val sizeInt: Int inline get() = size.toInt()

    /** The array of keys in the hash table. A value of EMPTY_KEY means that the slot is empty. */
    @DocDoNotUse
    var keys: UShortArray? = null

    /** The array of values in the hash table. */
    @DocDoNotUse @JvmField
    var values: IntArray? = null

    /** Gets the hash bucket used for the key, calculated using the current hash table array size. */
    private fun hashBucket(key: UShort): Int = key.toInt() % keys!!.size

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    operator fun set(key: UShort, value: Int): Boolean {
        if(keys == null) {
            keys = UShortArray(MIN_CAPACITY){EMPTY_KEY}
            values = IntArray(MIN_CAPACITY)
        }
        val keys = keys!!
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            // If the bucket is unoccupied, add this key into it
            if(keyAtBucket == EMPTY_KEY) {
                keys[bucket] = key
                values[bucket] = value
                size++
                // Grow the map if we are above the maximum load factor
                if(size.toDouble() > MAX_LOAD_FACTOR * keys.size) {
                    resize(keys.size * 2)
                }
                return true
            }
            // If the key is already in the map, change its value and return
            if(keyAtBucket == key) {
                values[bucket] = value
                return false
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun put(key: UShort, value: Int) = set(key, value)

    /**
     * Gets the value associated with the given key, or null if not found.
     *
     * @param key The key, must not be 65535
     * @return The value associated with the key
     */
    fun getOrNull(key: UShort): Int? {
        val keys = keys?:return null
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return null
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Gets the value associated with the given key, or a default value if not found.
     *
     * @param key The key, must not be 65535
     * @param default The default value returned if the key is not found. The "default" default value is zero.
     * @return The value associated with the key
     */
    operator fun get(key: UShort, default: Int = 0): Int {
        val keys = keys?:return default
        val values = values!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return default
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Returns whether this hash table contains the given key.
     *
     * @param key The key, must not be 65535
     * @return Whether the key exists in the hash table
     */
    fun contains(key: UShort): Boolean {
        val keys = keys?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            if(keyAtBucket == key) return true
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes the given key from this hash table, if it exists.
     *
     * @param key The key, must not be 65535
     * @return True if the key exists and was removed, false if the key did not exist and nothing was done
     */
    fun remove(key: UShort): Boolean {
        val keys = keys?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            // If we see the key, remove it and compress the array
            if(keyAtBucket == key) {
                size--
                // If the hash table is now empty, we can just remove the key and value arrays
                if(size.toInt() == 0) {
                    this.keys = null
                    this.values = null
                    return true
                }
                keys[bucket] = EMPTY_KEY
                // Shrink the map if we are below the minimum load factor, otherwise compress
                if(keys.size >= MIN_CAPACITY * 2 && size.toDouble() < MIN_LOAD_FACTOR * keys.size)
                    resize(keys.size / 2)
                else
                    compress(bucket)
                return true
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes all given keys from this hash table. Keys that do not exist in the hash table will be ignored.
     *
     * @param keys An array containing the given keys
     * @return The number of keys actually removed
     */
    fun removeAll(keys: UShortArray): Int = keys.count {remove(it)}

    /**
     * Called just after deleting an entry from a bucket. Repeatedly checks whether the next buckets can be moved into
     * the free slot, in order to compress the values and make sure that all remaining keys remain reachable from
     * their own buckets.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun compress(startingBucket: Int) {
        val keys = keys!!
        val values = values!!

        var freeBucket = startingBucket
        var bucket = (freeBucket + 1) % keys.size
        while(true) {
            val keyAtBucket = keys[bucket]
            // Return when reaching a free slot
            if(keyAtBucket == EMPTY_KEY) return
            val bucketOfKey = hashBucket(keyAtBucket)
            // Check whether this removal will create a free bucket between the natural bucket and current bucket of
            // this key. Test from https://stackoverflow.com/questions/9127207
            if((bucket > freeBucket && (bucketOfKey <= freeBucket || bucketOfKey > bucket)) ||
                (bucket < freeBucket && (bucketOfKey <= freeBucket && bucketOfKey > bucket))) {
                // Move current element
                keys[freeBucket] = keyAtBucket
                values[freeBucket] = values[bucket]
                // Mark current slot as free
                keys[bucket] = EMPTY_KEY
                freeBucket = bucket
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /** Resizes this hash table to a new size. The size must be enough to fit all existing data in the map. */
    private fun resize(newSize: Int) {
        val currentKeys = this.keys!!
        val currentValues = this.values!!

        val keys = UShortArray(newSize){EMPTY_KEY}
        this.keys = keys
        val values = IntArray(newSize)
        this.values = values

        // Copy data to the new arrays
        for(currentBucket in 0 until currentKeys.size) {
            val key = currentKeys[currentBucket]
            if(key == EMPTY_KEY) continue
            val value = currentValues[currentBucket]

            // The below is a copy of the "put" function, but without modifying size or checking for another resize
            var bucket = hashBucket(key)
            while(true) {
                val keyAtBucket = keys[bucket]
                // If the bucket is unoccupied, add this key into it
                if(keyAtBucket == EMPTY_KEY) {
                    keys[bucket] = key
                    values[bucket] = value
                    break
                }
                // If the key is already in the map, change its value and return
                if(keyAtBucket == key) {
                    values[bucket] = value
                    break
                }
                bucket = (bucket + 1) % keys.size
            }
        }
    }

    /**
     * Performs the given action for each entry of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key, value pair.
     */
    inline fun forEach(action: (UShort, Int) -> Unit) {
        val keys = keys?:return
        val values = values!!
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            val value = values[bucket]
            action(key, value)
        }
    }

    /**
     * Performs the given action for each key of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key in the hash table.
     */
    inline fun forEachKey(action: (UShort) -> Unit) {
        val keys = keys?:return
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            action(key)
        }
    }

    /**
     * Performs the given action for each value of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a value in the hash table.
     */
    inline fun forEachValue(action: (Int) -> Unit) {
        val keys = keys?:return
        val values = values!!
        for(bucket in 0 until keys.size) {
            val key = keys[bucket]
            if(key == EMPTY_KEY) continue
            val value = values[bucket]
            action(value)
        }
    }

    override fun equals(other: Any?): Boolean {
        if(other == null || other::class != this::class) return false
        other as UShortIntHashTable
        if(size != other.size) return false
        forEach{key, value ->
            if(!other.contains(key) || other[key] != value) return false
        }
        return true
    }
}

/**
 * An interface version of [UShortObjectHashTable].
 *
 * This allows a form of multiple inheritance where the object embeds the data of this hash table within itself
 * for memory savings, instead of using a reference to a standalone hash table.
 */
@OptIn(ExperimentalUnsignedTypes::class)
interface IUShortIntHashTable {
    companion object {
        /**
         * The special empty key of the UShortHashTable, with a value of 65535u. Do not use it as a key!
         */
        val EMPTY_KEY: UShort = 65535u
        private const val MIN_CAPACITY = 8
        private const val MAX_LOAD_FACTOR = 0.625
        private const val MIN_LOAD_FACTOR = 0.125 // Should be a lot less than half of the MAX_LOAD_FACTOR
    }

    /**
     * The current number of elements in the hash table.
     *
     * Must be implemented with the zero value, i.e. `0u.toUShort()`.
     */
    @DocStrictImplementation
    var sizeUSIHT: UShort

    /**
     * The current number of elements in the hash table, converted to an Int.
     */
    val sizeIntUSIHT: Int get() = sizeUSIHT.toInt()

    /** The array of keys in the hash table. A value of EMPTY_KEY means that the slot is empty.
     *
     * Must be implemented to start with a null value. */
    @DocDoNotUse @DocStrictImplementation
    var keysUSIHT: UShortArray?

    /** The array of values in the hash table.
     *
     * Must be implemented to start with a null value. */
    @DocDoNotUse @DocStrictImplementation
    var valuesUSIHT: IntArray?

    /** Gets the hash bucket used for the key, calculated using the current hash table array size. */
    private fun hashBucket(key: UShort): Int = key.toInt() % keysUSIHT!!.size

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun setUSIHT(key: UShort, value: Int): Boolean {
        if(keysUSIHT == null) {
            keysUSIHT = UShortArray(MIN_CAPACITY){EMPTY_KEY}
            valuesUSIHT = IntArray(MIN_CAPACITY)
        }
        val keys = keysUSIHT!!
        val values = valuesUSIHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            // If the bucket is unoccupied, add this key into it
            if(keyAtBucket == EMPTY_KEY) {
                keys[bucket] = key
                values[bucket] = value
                sizeUSIHT++
                // Grow the map if we are above the maximum load factor
                if(sizeUSIHT.toDouble() > MAX_LOAD_FACTOR * keys.size) {
                    resize(keys.size * 2)
                }
                return true
            }
            // If the key is already in the map, change its value and return
            if(keyAtBucket == key) {
                values[bucket] = value
                return false
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must not be 65535
     * @param value The value to associate with the key
     * @return True if the key was newly added; false if the key was already in the map and its associated value is
     * overridden instead.
     */
    fun putUSIHT(key: UShort, value: Int) = setUSIHT(key, value)

    /**
     * Gets the value associated with the given key, or null if not found.
     *
     * @param key The key, must not be 65535
     * @return The value associated with the key
     */
    fun getOrNullUSIHT(key: UShort): Int? {
        val keys = keysUSIHT?:return null
        val values = valuesUSIHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return null
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Gets the value associated with the given key, or a default value if not found.
     *
     * @param key The key, must not be 65535
     * @param default The default value returned if the key is not found. The "default" default value is zero.
     * @return The value associated with the key
     */
    fun getUSIHT(key: UShort, default: Int = 0): Int {
        val keys = keysUSIHT?:return default
        val values = valuesUSIHT!!

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return default
            if(keyAtBucket == key) return values[bucket]
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Returns whether this hash table contains the given key.
     *
     * @param key The key, must not be 65535
     * @return Whether the key exists in the hash table
     */
    fun containsUSIHT(key: UShort): Boolean {
        val keys = keysUSIHT?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            if(keyAtBucket == key) return true
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes the given key from this hash table, if it exists.
     *
     * @param key The key, must not be 65535
     * @return True if the key exists and was removed, false if the key did not exist and nothing was done
     */
    fun removeUSIHT(key: UShort): Boolean {
        val keys = keysUSIHT?:return false

        var bucket = hashBucket(key)
        while(true) {
            val keyAtBucket = keys[bucket]
            if(keyAtBucket == EMPTY_KEY) return false
            // If we see the key, remove it and compress the array
            if(keyAtBucket == key) {
                sizeUSIHT--
                // If the hash table is now empty, we can just remove the key and value arrays
                if(sizeUSIHT.toInt() == 0) {
                    this.keysUSIHT = null
                    this.valuesUSIHT = null
                    return true
                }
                keys[bucket] = EMPTY_KEY
                // Shrink the map if we are below the minimum load factor, otherwise compress
                if(keys.size >= MIN_CAPACITY * 2 && sizeUSIHT.toDouble() < MIN_LOAD_FACTOR * keys.size)
                    resize(keys.size / 2)
                else
                    compress(bucket)
                return true
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /**
     * Removes all given keys from this hash table. Keys that do not exist in the hash table will be ignored.
     *
     * @param keys An array containing the given keys
     * @return The number of keys actually removed
     */
    fun removeAllUSIHT(keys: UShortArray): Int = keys.count {removeUSIHT(it)}

    /**
     * Called just after deleting an entry from a bucket. Repeatedly checks whether the next buckets can be moved into
     * the free slot, in order to compress the values and make sure that all remaining keys remain reachable from
     * their own buckets.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun compress(startingBucket: Int) {
        val keys = keysUSIHT!!
        val values = valuesUSIHT!!

        var freeBucket = startingBucket
        var bucket = (freeBucket + 1) % keys.size
        while(true) {
            val keyAtBucket = keys[bucket]
            // Return when reaching a free slot
            if(keyAtBucket == EMPTY_KEY) return
            val bucketOfKey = hashBucket(keyAtBucket)
            // Check whether this removal will create a free bucket between the natural bucket and current bucket of
            // this key. Test from https://stackoverflow.com/questions/9127207
            if((bucket > freeBucket && (bucketOfKey <= freeBucket || bucketOfKey > bucket)) ||
                (bucket < freeBucket && (bucketOfKey <= freeBucket && bucketOfKey > bucket))) {
                // Move current element
                keys[freeBucket] = keyAtBucket
                values[freeBucket] = values[bucket]
                // Mark current slot as free
                keys[bucket] = EMPTY_KEY
                freeBucket = bucket
            }
            bucket = (bucket + 1) % keys.size
        }
    }

    /** Resizes this hash table to a new size. The size must be enough to fit all existing data in the map. */
    private fun resize(newSize: Int) {
        val currentKeys = this.keysUSIHT!!
        val currentValues = this.valuesUSIHT!!

        val keys = UShortArray(newSize){EMPTY_KEY}
        this.keysUSIHT = keys
        val values = IntArray(newSize)
        this.valuesUSIHT = values

        // Copy data to the new arrays
        for(currentBucket in 0 until currentKeys.size) {
            val key = currentKeys[currentBucket]
            if(key == EMPTY_KEY) continue
            val value = currentValues[currentBucket]

            // The below is a copy of the "put" function, but without modifying size or checking for another resize
            var bucket = hashBucket(key)
            while(true) {
                val keyAtBucket = keys[bucket]
                // If the bucket is unoccupied, add this key into it
                if(keyAtBucket == EMPTY_KEY) {
                    keys[bucket] = key
                    values[bucket] = value
                    break
                }
                // If the key is already in the map, change its value and return
                if(keyAtBucket == key) {
                    values[bucket] = value
                    break
                }
                bucket = (bucket + 1) % keys.size
            }
        }
    }

    /**
     * Returns whether this hash table's contents are the same as the other hash table, i.e. both hash tables
     * have the same size and the same set of keys, and each key has the same value.
     */
    fun equalsUSIHT(other: IUShortIntHashTable): Boolean {
        if(sizeUSIHT != other.sizeUSIHT) return false
        forEachUSIHT{key, value ->
            if(!other.containsUSIHT(key) || other.getUSIHT(key) != value) return false
        }
        return true
    }
}

/**
 * A resizing hash table with 24-bit unsigned integer keys and UByte values, which uses open addressing with linear
 * probing with a maximum load factor of 2/3, minimum load factor of 1/6, and initial/minimum capacity of 8.
 *
 * This is a compact hash table, meaning that its load factor values are tuned primarily for memory efficiency,
 * and it only uses a single array and is an inline class wrapped over the array. Resizing the hash table is done by
 * returning a reference to a new hash table. The size (number of entries) is stored as the last value of the data
 * array.
 *
 * The 16777215 key is not supported, while all of 0..16777214 are supported.
 * The hash used for the key is just the key itself, so the keys should be some kind of ID with no inherent meaning to
 * help avoid clustering.
 *
 * This hash table is not thread-safe and does not properly implement hashCode().
 */
@JvmInline
value class UInt24UByteCompactHashTable(
    /**
     * The data array of the hash table.
     * Note that the last value denotes the size of the hash table.
     * Each value prior to the last value denotes a 24-bit key + 8-bit value pair.
     */
    val data: IntArray
) {
    /**
     * Constructs an empty hash table.
     */
    constructor(): this(IntArray(MIN_CAPACITY + 1){i -> if(i == MIN_CAPACITY) 0 else EMPTY_KEY.shl(8)})

    /**
     * Constructs an empty hash table with the given starting capacity.
     * The given capacity should be a power of 2.
     *
     * Note that the given capacity is not the minimum capacity, and when removing values the hash table could
     * still shrink below the provided capacity. Therefore, this constructor is most useful for hash tables that do not
     * expect to remove any values.
     */
    constructor(capacity: Int): this(IntArray(capacity + 1){i -> if(i == capacity) 0 else EMPTY_KEY.shl(8)})

    companion object {
        /**
         * The special empty key of the hash table, with a value of 16777215. Do not use it as a key!
         */
        const val EMPTY_KEY: Int = 16777215

        /**
         * The min capacity of the hash table. Note that the actual array size is 1 larger than this to store the
         * current size of the hash table.
         */
        private const val MIN_CAPACITY = 8
        private const val MAX_LOAD_FACTOR = 0.66666667
        private const val MIN_LOAD_FACTOR = 0.166666667 // Should be a lot less than half of the MAX_LOAD_FACTOR
    }

    /**
     * The current number of elements in the hash table.
     */
    inline var size: Int get() = data[data.size - 1]
        private set(value) {data[data.size - 1] = value}

    /** Gets the hash bucket used for the key, calculated using the current hash table array size. */
    private fun hashBucket(key: Int): Int = key % data.size

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must be between 0..16777214
     * @param value The value to associate with the key
     * @return The new hash table reference. It may point to this object, or a new resized hash table.
     * In either case you should override the hash table reference with the one returned by this function.
     */
    operator fun set(key: Int, value: UByte): UInt24UByteCompactHashTable {
        var bucket = hashBucket(key)
        while(true) {
            val dataAtBucket = data[bucket]
            val keyAtBucket = dataAtBucket.shr(8)

            // If the bucket is unoccupied, add this key into it
            if(keyAtBucket == EMPTY_KEY) {
                data[bucket] = key.shl(8) + value.toInt()
                size++
                // Grow the map if we are above the maximum load factor
                return if(size.toDouble() > MAX_LOAD_FACTOR * data.size)
                    resize((data.size - 1) * 2)
                else this
            }
            // If the key is already in the map, change its value and return
            if(keyAtBucket == key) {
                data[bucket] = key.shl(8) + value.toInt()
                return this
            }
            bucket = (bucket + 1) % data.size
        }
    }

    /**
     * Sets the given value to be associated with the given key. If the key already exists, its corresponding value
     * is overridden with the new value instead.
     *
     * @param key The key, must be between 0..16777214
     * @param value The value to associate with the key
     * @return The new hash table reference. It may point to this object, or a new resized hash table.
     * In either case you should override the hash table reference with the one returned by this function.
     */
    fun put(key: Int, value: UByte) = set(key, value)

    /**
     * Gets the value associated with the given key, or null if not found.
     *
     * @param key The key, must be between 0..16777214
     * @return The value associated with the key
     */
    fun getOrNull(key: Int): UByte? {
        var bucket = hashBucket(key)
        while(true) {
            val dataAtBucket = data[bucket]
            val keyAtBucket = dataAtBucket.shr(8)
            if(keyAtBucket == EMPTY_KEY) return null
            if(keyAtBucket == key) return dataAtBucket.toUByte()
            bucket = (bucket + 1) % data.size
        }
    }

    /**
     * Gets the value associated with the given key, or a default value if not found.
     *
     * @param key The key, must be between 0..16777214
     * @param default The default value returned if the key is not found. The "default" default value is zero.
     * @return The value associated with the key
     */
    operator fun get(key: Int, default: UByte = 0u): UByte {
        var bucket = hashBucket(key)
        while(true) {
            val dataAtBucket = data[bucket]
            val keyAtBucket = dataAtBucket.shr(8)
            if(keyAtBucket == EMPTY_KEY) return default
            if(keyAtBucket == key) return dataAtBucket.toUByte()
            bucket = (bucket + 1) % data.size
        }
    }

    /**
     * Returns whether this hash table contains the given key.
     *
     * @param key The key, must be between 0..16777214
     * @return Whether the key exists in the hash table
     */
    fun contains(key: Int): Boolean {
        var bucket = hashBucket(key)
        while(true) {
            val dataAtBucket = data[bucket]
            val keyAtBucket = dataAtBucket.shr(8)
            if(keyAtBucket == EMPTY_KEY) return false
            if(keyAtBucket == key) return true
            bucket = (bucket + 1) % data.size
        }
    }

    /**
     * Removes the given key from this hash table, if it exists.
     *
     * @param key The key, must be between 0..16777214
     * @return The new hash table reference. It may point to this object, or a new resized hash table.
     * In either case you should override the hash table reference with the one returned by this function.
     */
    fun remove(key: Int): UInt24UByteCompactHashTable {
        var bucket = hashBucket(key)
        while(true) {
            val dataAtBucket = data[bucket]
            val keyAtBucket = dataAtBucket.shr(8)
            if(keyAtBucket == EMPTY_KEY) return this
            // If we see the key, remove it and compress the array
            if(keyAtBucket == key) {
                size--
                data[bucket] = EMPTY_KEY
                // Shrink the map if we are below the minimum load factor, otherwise compress
                return if(data.size >= MIN_CAPACITY * 2 && size.toDouble() < MIN_LOAD_FACTOR * data.size)
                    resize((data.size - 1) / 2)
                else {
                    compress(bucket)
                    this
                }
            }
            bucket = (bucket + 1) % data.size
        }
    }

    /**
     * Removes all given keys from this hash table. Keys that do not exist in the hash table will be ignored.
     *
     * @param keys An array containing the given keys
     * @return The new hash table reference. It may point to this object, or a new resized hash table.
     * In either case you should override the hash table reference with the one returned by this function.
     */
    fun removeAll(keys: IntArray): UInt24UByteCompactHashTable {
        var currentRef = this
        for(key in keys) currentRef = currentRef.remove(key)
        return currentRef
    }

    /**
     * Called just after deleting an entry from a bucket. Repeatedly checks whether the next buckets can be moved into
     * the free slot, in order to compress the values and make sure that all remaining keys remain reachable from
     * their own buckets.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun compress(startingBucket: Int) {
        var freeBucket = startingBucket
        var bucket = (freeBucket + 1) % data.size
        while(true) {
            val dataAtBucket = data[bucket]
            val keyAtBucket = dataAtBucket.shr(8)
            // Return when reaching a free slot
            if(keyAtBucket == EMPTY_KEY) return
            val bucketOfKey = hashBucket(keyAtBucket)
            // Check whether this removal will create a free bucket between the natural bucket and current bucket of
            // this key. Test from https://stackoverflow.com/questions/9127207
            if((bucket > freeBucket && (bucketOfKey <= freeBucket || bucketOfKey > bucket)) ||
                (bucket < freeBucket && (bucketOfKey <= freeBucket && bucketOfKey > bucket))) {
                // Move current element
                data[freeBucket] = dataAtBucket
                // Mark current slot as free
                data[bucket] = EMPTY_KEY.shl(8)
                freeBucket = bucket
            }
            bucket = (bucket + 1) % data.size
        }
    }

    /** Resizes this hash table to a new size. The size must be enough to fit all existing data in the map. */
    private fun resize(newSize: Int) : UInt24UByteCompactHashTable {
        // Add additional 1 entry to the array to store array size
        val newData = IntArray(newSize + 1){EMPTY_KEY.shl(8)}

        // Set last entry of the array to the new size
        newData[newSize] = size

        // Copy data to the new array
        for(currentBucket in 0 until data.size) {
            val dataCurrent = data[currentBucket]
            val key = dataCurrent.shr(8)
            if(key == EMPTY_KEY) continue

            // The below is a copy of the "put" function, but without modifying size or checking for another resize
            var bucket = hashBucket(key)
            while(true) {
                val dataAtBucket = newData[bucket]
                val keyAtBucket = dataAtBucket.shr(8)
                // If the bucket is unoccupied, add this key into it
                if(keyAtBucket == EMPTY_KEY) {
                    newData[bucket] = dataCurrent
                    break
                }
                // If the key is already in the map, change its value and return
                if(keyAtBucket == key) {
                    newData[bucket] = dataCurrent
                    break
                }
                bucket = (bucket + 1) % newData.size
            }
        }
        return UInt24UByteCompactHashTable(newData)
    }

    /**
     * Performs the given action for each entry of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key, value pair.
     */
    inline fun forEach(action: (Int, UByte) -> Unit) {
        for(bucket in 0 until data.size) {
            val dataAtBucket = data[bucket]
            val key = dataAtBucket.shr(8)
            if(key == EMPTY_KEY) continue
            val value = dataAtBucket.toUByte()
            action(key, value)
        }
    }

    /**
     * Performs the given action for each key of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a key in the hash table.
     */
    inline fun forEachKey(action: (Int) -> Unit) {
        for(bucket in 0 until data.size) {
            val dataAtBucket = data[bucket]
            val key = dataAtBucket.shr(8)
            if(key == EMPTY_KEY) continue
            action(key)
        }
    }

    /**
     * Performs the given action for each value of the hash table. The iteration will be performed in arbitrary order.
     *
     * @param action An action to execute given a value in the hash table.
     */
    inline fun forEachValue(action: (UByte) -> Unit) {
        for(bucket in 0 until data.size) {
            val dataAtBucket = data[bucket]
            val key = dataAtBucket.shr(8)
            if(key == EMPTY_KEY) continue
            val value = dataAtBucket.toUByte()
            action(value)
        }
    }
}
