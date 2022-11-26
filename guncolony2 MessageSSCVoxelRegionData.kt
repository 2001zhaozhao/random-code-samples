package com.guncolony.common.networking.messages.voxel

import com.guncolony.common.networking.NetworkMessageFactory
import com.guncolony.common.networking.SCNetworkMessage
import com.guncolony.common.networking.SSNetworkMessage
import com.guncolony.common.voxel.CommonVoxelData
import com.guncolony.util.*

/**
 * A message that is sent from the server to either a client or another server to update a 3D AABB region of blocks in
 * a voxel volume, including their block ID, lighting data, and biome data.
 *
 * This is most often used to send the data of an entire chunk in a world, or initialize a smaller voxel volume
 * completely after it is created on the server.
 *
 * This is the first actual gameplay message 3 months into the GC server's development. Hooray!
 */
class MessageSSCVoxelRegionData(
    /**
     * The ID of the voxel volume on the client.
     */
    val voxelVolumeId: Int,

    /**
     * The anchor block representing which voxel volume position corresponds to the (0,0,0) relative position in the
     * [data] object.
     *
     * Essentially, `positionInVoxelVolume - anchorBlockPos = positionInDataObject`
     */
    val anchorBlockPos: BlockVec,

    /**
     * The data object containing the actual voxel data as well as the size of the voxel region being sent
     */
    val data: CommonVoxelData
) : SCNetworkMessage, SSNetworkMessage {
    override val scMessageTypeId: Int get() = 10
    override val ssMessageTypeId: Int get() = 10

    val encoded by lazy {
        snappyCompress(run {
            val numBlocks = data.sizeX * data.sizeY * data.sizeZ
            val array = ByteArray(numBlocks * 7)
            data.blockStateArray.let {
                for(i in it.indices) array.writeInt(i*4, it[i])
            }
            data.biomeArray.let {
                for(i in it.indices) array.writeShort(numBlocks*4 + i*2, it[i])
            }
            data.lightingArray.let {
                for(i in it.indices) array[numBlocks*6 + i] = it[i]
            }
            array
        })
    }

    override fun writeBytes(bytes: ByteArray, position: MutableInt) {
        bytes.writeInt(position, voxelVolumeId)
        bytes.writeULong(position, anchorBlockPos.value)
        bytes.writeInt(position, data.sizeX)
        bytes.writeInt(position, data.sizeY)
        bytes.writeInt(position, data.sizeZ)
        bytes.writeByteArrayWithLengthLong(position, encoded)
    }

    override fun measureBytes(): Int = 4 + 8 + 4 + 4 + 4 + (4 + encoded.size)

    companion object : NetworkMessageFactory<MessageSSCVoxelRegionData> {
        override val type = MessageSSCVoxelRegionData::class

        override fun readBytes(bytes: ByteArray, position: MutableInt) = MessageSSCVoxelRegionData(
            bytes.readInt(position),
            BlockVec(bytes.readLong(position)),
            run {
                val x = bytes.readInt(position)
                val y = bytes.readInt(position)
                val z = bytes.readInt(position)
                val numBlocks = x * y * z
                val array = snappyDecompress(bytes.readByteArrayWithLengthLong(position))
                CommonVoxelData(
                    x, y, z,
                    IntArray(numBlocks){i -> array.readInt(i*4)},
                    ShortArray(numBlocks){i -> array.readShort(numBlocks*4 + i*2)},
                    ByteArray(numBlocks){i -> array[numBlocks*6 + i]}
                )
            }
        )
    }

    // Slightly longer resend interval since these messages are big and usually not terribly important
    override val resendIntervalMillis: Int get() = 300
}
