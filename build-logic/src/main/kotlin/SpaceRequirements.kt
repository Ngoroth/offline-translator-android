object SpaceRequirements {
    const val HOST_BYTES=8L*1024*1024*1024
    const val DEVICE_BYTES=4L*1024*1024*1024
    fun host(available: Long, pendingBytes: Long = 0) {
        require(pendingBytes>=0)
        require(available>=maxOf(HOST_BYTES,pendingBytes)) {"Insufficient host preparation space (8 GiB minimum)"}
    }
    fun device(availableKiB: Long) {
        require(availableKiB>=DEVICE_BYTES/1024) {"Emulator free space below 4 GiB"}
    }
}
