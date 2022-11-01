package com.guncolony.common.networking

/**
 * Sequence buffer handles the receiving of long packets that need to be split into multiple fragments. It is meant
 * to collect them back together and recreate the original packet efficiently.
 *
 * A sequence buffer stores incoming packets that have multiple fragments, and keeps the fragments until all fragments
 * have been received.
 *
 * The implementation is based on
 * [Gaffer on Games' guide](https://gafferongames.com/post/packet_fragmentation_and_reassembly/)
 *
 * This class is not thread-safe, so it should usually only be used from the network thread.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class PacketFragmentSequenceBuffer {
    /**
     * An entry in the SequenceBuffer represents one packet that needs to be combined.
     * It is stored as an array of length equal to the number of fragments in the packet,
     * with each value in the array being a packet fragment.
     */
    private val entries = Array<Array<ByteArray?>?>(MAX_ENTRIES){null}
    private val sequenceIds = UShortArray(MAX_ENTRIES)
    private val numFragmentsReceived = UByteArray(MAX_ENTRIES)

    companion object {
        /**
         * The number of sequential packet sequence IDs that this buffer can store
         */
        const val MAX_ENTRIES = 256
    }

    /**
     * Adds a packet fragment to this SequenceBuffer.
     *
     * If a complete packet was formed with the addition of this fragment, it will be returned. Otherwise, the method
     * will return null. A packet will never be returned twice.
     *
     * Note that a packet that has not been completed before receiving [MAX_ENTRIES] other packets
     * will likely never be completed.
     *
     * @return A completed packet, or null if the packet is not completed by this fragment
     */
    fun addPacketFragment(packet: ByteArray, sequenceId: UShort, numFragments: UByte, fragmentId: UByte): ByteArray? {
        if(fragmentId >= numFragments) return null
        val index = sequenceId.toInt() % MAX_ENTRIES

        // Get the entry corresponding to this sequenceId, or create a new entry if the previous one is outdated
        var entry = entries[index]
        if(entry == null || entry.size != numFragments.toInt() || sequenceIds[index] != sequenceId) {
            entry = Array(numFragments.toInt()){null}
            entries[index] = entry
            sequenceIds[index] = sequenceId
            numFragmentsReceived[index] = 0u
        }
        else {
            if(fragmentId.toInt() >= entry.size) return null
        }
        val fragmentIndex = fragmentId.toInt()

        // Add this fragment to the entry we just obtained/created
        // Only add this fragment if the entry does not already contain a fragment at this index
        if(entry[fragmentIndex] == null) {
            entry[fragmentIndex] = packet
            val newNumFragmentsReceived = (numFragmentsReceived[index] + 1u).toUByte()
            numFragmentsReceived[index] = newNumFragmentsReceived

            // If we have all the pieces of the new packet, we can now create the full packet and return it
            if(newNumFragmentsReceived.toInt() == entry.size) {
                // Get the total size of all entries
                val totalSize = entry.sumOf{it!!.size}
                val fullPacket = ByteArray(totalSize)
                var fullPacketIndex = 0
                // Concatenate all byte arrays together into one array
                for(fragment in entry) {
                    for(i in 0 until fragment!!.size) {
                        fullPacket[fullPacketIndex] = fragment[i]
                        fullPacketIndex++
                    }
                }
                return fullPacket
            }
        }
        return null
    }
}
