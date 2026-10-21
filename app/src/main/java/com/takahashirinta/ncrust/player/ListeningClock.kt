package com.takahashirinta.ncrust.player

/** Actual isPlaying wall time on an injected monotonic clock; positions/seeks are not inputs. */
internal class ListeningClock(private val now:()->Long) {
    var songId:Long?=null; private set
    private var playing=false
    private var anchor=now()
    private var listened=0L
    fun begin(id:Long,restart:Boolean=false):Boolean {
        settle()
        if(id==songId && !restart)return false
        songId=id;listened=0L
        return true
    }
    fun setPlaying(value:Boolean) {settle();playing=value}
    fun playedMs(id:Long):Long {settle();return if(songId==id)listened else 0L}
    private fun settle() {
        val time=now()
        if(playing && songId!=null && time>=anchor)listened+=(time-anchor)
        anchor=time
    }
}
