package com.guncolony.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmField
import kotlin.math.*

/**
 * Represents a 3-dimensional **mutable** vector of Double values.
 *
 * This class is roughly based on the Vector3 class in the KorMA library.
 *
 * **Important:** some functions in this class are mutators that mutate the current vector, while others create
 * new vectors. This is always mentioned in the "Returns:" section in the function's documentation.
 *
 * For an immutable version, use [IVec].
 */
@Suppress("NOTHING_TO_INLINE", "MemberVisibilityCanBePrivate", "unused", "DuplicatedCode")
@Serializable(with = Vec.VecArraySerializer::class)
data class Vec(
    /**
     * The x value of this vector.
     */
    @JvmField var x: Double,
    /**
     * The y value of this vector.
     */
    @JvmField var y: Double,
    /**
     * The z value of this vector.
     */
    @JvmField var z: Double
) {
    /**
     * Constructs a zero vector.
     */
    constructor() : this(0.0, 0.0, 0.0)

    /**
     * Constructs a [Vec] with integer coordinates, which will be converted to double.
     */
    constructor(x: Int, y: Int, z: Int) : this(x.toDouble(), y.toDouble(), z.toDouble())

    /**
     * Constructs a [Vec] with float coordinates, which will be converted to double.
     */
    constructor(x: Float, y: Float, z: Float) : this(x.toDouble(), y.toDouble(), z.toDouble())

    /**
     * The squared Euclidean length of this vector.
     */
    val lengthSquared: Double inline get() = (x * x) + (y * y) + (z * z)

    /**
     * The Euclidean length of this vector.
     */
    val length: Double inline get() = sqrt(lengthSquared)

    /**
     * Creates a new vector which contains the same values as this vector.
     *
     * @return A new vector
     */
    inline fun clone() = Vec(x, y, z)

    /**
     * Gets the specified index of this vector.
     *
     * @param index The index. 0 returns x, 1 returns y, 2 returns z
     * @throws IllegalStateException if index is not between 0..2
     */
    operator fun get(index: Int): Double = when(index) {
        0 -> x
        1 -> y
        2 -> z
        else -> error("Array index must be between 0..2 to get an element of a 3D vector")
    }

    /**
     * Sets the specified index of this vector.
     *
     * @param index The index. 0 sets x, 1 sets y, 2 sets z
     * @throws IllegalStateException if index is not between 0..2
     * @return This vector
     */
    operator fun set(index: Int, value: Double): Vec {
        when(index) {
            0 -> x = value
            1 -> y = value
            2 -> z = value
            else -> error("Array index must be between 0..2 to set an element of a 3D vector")
        }
        return this
    }

    /**
     * Sets the x,y,z values of this vector to the specified values.
     *
     * @return This vector
     */
    inline fun setTo(x: Double, y: Double, z: Double): Vec {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    /**
     * Sets the elements of this vector to be the same as the given parameter vector.
     *
     * @return This vector
     */
    inline fun setTo(other: Vec): Vec = setTo(other.x, other.y, other.z)

    /**
     * Sets the elements of this vector to the specified value according to the given function.
     *
     * @param func The function that when given an index between 0-2 corresponding to x-z, returns the value that
     * this vector should be set to
     * @return This vector
     */
    inline fun setToFunc(func: (index: Int) -> Double): Vec = setTo(func(0), func(1), func(2))

    /**
     * Sets the elements of this vector by performing a pairwise operation between each element of the two provided
     * vectors.
     *
     * @param func The pairwise operation that determines the new vector's x value from the x values of the two
     * given vectors, and same for the y and z values
     * @return This vector
     */
    inline fun setToFunc(left: Vec, right: Vec, func: (l: Double, r: Double) -> Double): Vec = setTo(
        func(left.x, right.x),
        func(left.y, right.y),
        func(left.z, right.z)
    )

    /**
     * Sets the elements of this vector to the interpolated value of the left and right vectors with the given t
     * parameter value.
     *
     * For example, if t = 0.0, then this vector will be set to the left vector. If t = 1.0, then this vector
     * will be set to the right vector.
     *
     * This function will work correctly even if this vector is also one of the parameter vectors, and/or t is outside
     * the range of 0-1.
     *
     * For the version that creates a new vector, use `Double.interpolate()` from ExtensionMath.kt.
     *
     * @return This vector
     */
    fun setToInterpolated(left: Vec, right: Vec, t: Double): Vec {
        x = right.x * t + left.x * (1 - t)
        y = right.y * t + left.y * (1 - t)
        z = right.z * t + left.z * (1 - t)
        return this
    }

    /**
     * Mutates the values of this vector, multiplying them by the given scale value.
     *
     * This is different from the "times" operator since this function mutates the current vector, while the "times"
     * operator creates a new vector.
     *
     * @param scale The scale to multiply by
     * @return This vector
     */
    inline fun multiply(scale: Double): Vec = setTo(x * scale, y * scale, z * scale)

    /**
     * Creates a new vector contains the values of this vector multiplied with the given scale value.
     *
     * @param scale The scale to multiply by
     * @return A new vector
     */
    operator fun times(scale: Double): Vec = clone().multiply(scale)

    /**
     * Mutates the values of this vector, multiplying them by the given scale value.
     *
     * This is different from the "times" operator since this function mutates the current vector, while the "times"
     * operator creates a new vector.
     *
     * @param scale The scale to multiply by
     * @return This vector
     */
    inline fun multiply(scale: Float): Vec = setTo(x * scale, y * scale, z * scale)

    /**
     * Creates a new vector contains the values of this vector multiplied with the given scale value.
     *
     * @param scale The scale to multiply by
     * @return A new vector
     */
    operator fun times(scale: Float): Vec = clone().multiply(scale)

    /**
     * Mutates the values of this vector, multiplying them by the given scale value.
     *
     * This is different from the "times" operator since this function mutates the current vector, while the "times"
     * operator creates a new vector.
     *
     * @param scale The scale to multiply by
     * @return This vector
     */
    inline fun multiply(scale: Int): Vec = setTo(x * scale, y * scale, z * scale)

    /**
     * Creates a new vector contains the values of this vector multiplied with the given scale value.
     *
     * @param scale The scale to multiply by
     * @return A new vector
     */
    operator fun times(scale: Int): Vec = clone().multiply(scale)

    /**
     * Returns whether this vector is the zero vector.
     *
     * If you need to check for catastrophic cancellation, use [almostEqualsZero].
     */
    val isZero: Boolean get() = x == 0.0 && y == 0.0 && z == 0.0

    /**
     * Mutates this vector so that it has a length of 1.
     *
     * Note that this function is not safe for zero vectors and will set all values to infinity or NaN.
     * Check with [isZero] first or use [normalizeSafe].
     *
     * @return This vector
     */
    inline fun normalize(): Vec {
        val norm = 1.0 / length
        return multiply(norm)
    }

    /**
     * Creates a new vector that equals the normalized version of this vector.
     *
     * Note that this function is not safe for zero vectors and will return a vector with all values to infinity or NaN.
     * Check with [isZero] first or use [normalizedSafe].
     *
     * @return A new vector
     */
    val normalized: Vec inline get() {
        val norm = 1.0 / length
        return Vec(x * norm, y * norm, z * norm)
    }

    /**
     * If this vector is the zero vector, does nothing and returns the same zero vector.
     * If this vector is not the zero vector, mutates it so that it has a length of 1.
     *
     * @return This vector
     */
    inline fun normalizeSafe(): Vec {
        if(isZero) return this
        val norm = 1.0 / length
        return multiply(norm)
    }

    /**
     * If this vector is the zero vector, returns a new zero vector.
     * If this vector is not the zero vector, returns a new vector that is the normalized version of this vector.
     *
     * @return A new vector
     */
    val normalizedSafe: Vec inline get() {
        if(isZero) return Vec()
        return normalized
    }

    /**
     * Returns the dot product of this vector with another vector.
     *
     * @return The dot product
     */
    inline fun dot(other: Vec): Double = x * other.x + y * other.y + z * other.z

    /**
     * Returns a new vector containing the sum of this vector with the other vector.
     *
     * @return A new vector
     */
    inline operator fun plus(other: Vec): Vec = Vec(x + other.x, y + other.y, z + other.z)

    /**
     * Returns a new vector containing the difference between this vector and the other vector.
     *
     * @return A new vector
     */
    inline operator fun minus(other: Vec): Vec = Vec(x - other.x, y - other.y, z - other.z)

    /**
     * Mutates the values of this vector, adding it with another vector.
     *
     * @return This vector
     */
    inline fun add(other: Vec): Vec {
        x += other.x
        y += other.y
        z += other.z
        return this
    }

    /**
     * Mutates the values of this vector, subtracting it with another vector.
     *
     * @return This vector
     */
    inline fun subtract(other: Vec): Vec {
        x -= other.x
        y -= other.y
        z -= other.z
        return this
    }

    /**
     * Sets the values of this vector the cross product of this vector with the other vector.
     *
     * This function is a **mutator**. The version that creates a new vector is [cross].
     *
     * @return This vector
     */
    inline fun crossWith(other: Vec): Vec = setTo(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    /**
     * Returns a new vector containing the cross product of this vector with another vector.
     *
     * This is an infix function, so you can use the notation `v1 cross v2`.
     *
     * @return A new vector
     */
    inline infix fun cross(other: Vec): Vec = Vec(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    /**
     * Returns whether this vector is almost equal to the other vector.
     *
     * This is true iff all three of the values are almost equal to one another between the two vectors.
     * The comparison between two numbers is done using the `Double.almostEquals` function in ExtensionMath.kt;
     * consult its documentation for the exact details.
     */
    inline infix fun almostEquals(other: Vec): Boolean =
        x almostEquals other.x && y almostEquals other.y && z almostEquals other.z

    /**
     * Returns whether this vector is almost equal to zero.
     *
     * This is true iff the absolute values of all three of the values are at most 10^-12.
     */
    val almostEqualsZero: Boolean inline get() =
        abs(x) <= M.DOUBLE_ALMOST_EQUALS_THRESHOLD &&
                abs(y) <= M.DOUBLE_ALMOST_EQUALS_THRESHOLD &&
                abs(z) <= M.DOUBLE_ALMOST_EQUALS_THRESHOLD

    // ========== Array serializer

    /**
     * A serializer to serialize Vec into a file as an array with 3 double values. It is used as the default
     * serializer for the [Vec] type because it is usually shorter than the { x: 0, y: 0, z: 0 } format.
     */
    object VecArraySerializer : KSerializer<Vec> {
        private val delegateSerializer = DoubleArraySerializer()

        override fun deserialize(decoder: Decoder): Vec {
            val array = decoder.decodeSerializableValue(delegateSerializer)
            return Vec(array[0], array[1], array[2])
        }

        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor
            get() = SerialDescriptor("FloatVec", delegateSerializer.descriptor)

        override fun serialize(encoder: Encoder, value: Vec) {
            val data = doubleArrayOf(value.x, value.y, value.z)
            encoder.encodeSerializableValue(delegateSerializer, data)
        }
    }

    // ========== Interop with other vector types

    /**
     * Returns a new vector containing the sum of this vector with the other vector.
     *
     * @return A new vector
     */
    inline operator fun plus(other: FloatVec): Vec = Vec(x + other.x, y + other.y, z + other.z)

    /**
     * Returns a new vector containing the difference between this vector and the other vector.
     *
     * @return A new vector
     */
    inline operator fun minus(other: FloatVec): Vec = Vec(x - other.x, y - other.y, z - other.z)

    /**
     * Mutates the values of this vector, adding it with another vector.
     *
     * @return This vector
     */
    inline fun add(other: FloatVec): Vec {
        x += other.x
        y += other.y
        z += other.z
        return this
    }

    /**
     * Mutates the values of this vector, subtracting it with another vector.
     *
     * @return This vector
     */
    inline fun subtract(other: FloatVec): Vec {
        x -= other.x
        y -= other.y
        z -= other.z
        return this
    }

    /**
     * Returns a new vector containing the sum of this vector with the other vector.
     *
     * @return A new vector
     */
    inline operator fun plus(other: BlockVec): Vec = Vec(x + other.x, y + other.y, z + other.z)

    /**
     * Returns a new vector containing the difference between this vector and the other vector.
     *
     * @return A new vector
     */
    inline operator fun minus(other: BlockVec): Vec = Vec(x - other.x, y - other.y, z - other.z)

    /**
     * Mutates the values of this vector, adding it with another vector.
     *
     * @return This vector
     */
    inline fun add(other: BlockVec): Vec {
        x += other.x
        y += other.y
        z += other.z
        return this
    }

    /**
     * Mutates the values of this vector, subtracting it with another vector.
     *
     * @return This vector
     */
    inline fun subtract(other: BlockVec): Vec {
        x -= other.x
        y -= other.y
        z -= other.z
        return this
    }

    /**
     * Returns a new vector containing the sum of this vector with the other vector.
     *
     * @return A new vector
     */
    inline operator fun plus(other: ShortVec): Vec = Vec(x + other.x, y + other.y, z + other.z)

    /**
     * Returns a new vector containing the difference between this vector and the other vector.
     *
     * @return A new vector
     */
    inline operator fun minus(other: ShortVec): Vec = Vec(x - other.x, y - other.y, z - other.z)

    /**
     * Mutates the values of this vector, adding it with another vector.
     *
     * @return This vector
     */
    inline fun add(other: ShortVec): Vec {
        x += other.x
        y += other.y
        z += other.z
        return this
    }

    /**
     * Mutates the values of this vector, subtracting it with another vector.
     *
     * @return This vector
     */
    inline fun subtract(other: ShortVec): Vec {
        x -= other.x
        y -= other.y
        z -= other.z
        return this
    }

    /**
     * Gets the [BlockVec] representing the block location at the position of this vector.
     *
     * This value is obtained by rounding down the x,y,z coordinates (towards negative infinity), then converting it
     * to a BlockVec after a bounds check.
     *
     * This method will return [BlockVec.NIL] if this vector is out of bounds for a BlockVec.
     */
    val toBlockVec: BlockVec get() = BlockVec.getOrNil(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())

    /**
     * Converts this [Vec] representing a block coordinate to the chunk coordinate of a chunk.
     *
     * The result is equal to `IntPair(floor(x) shr 4, floor(z) shr 4)`.
     */
    val chunk: IntPair inline get() = IntPair(floor(x).toInt().shr(4), floor(z).toInt().shr(4))
}
