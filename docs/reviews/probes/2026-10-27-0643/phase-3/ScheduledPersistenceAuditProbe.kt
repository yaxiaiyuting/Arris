package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ScheduledPersistenceAuditProbe {
    private class DiskPrefs(val disk:MutableMap<String,Any?> = mutableMapOf()):SharedPreferences by MemoryPrefs(disk.toMutableMap()) {
        val memory=MemoryPrefs(disk.toMutableMap())
        var fail=false
        override fun getString(key:String,fallback:String?):String?=memory.getString(key,fallback)
        override fun edit():SharedPreferences.Editor {
            val editor=memory.edit()
            return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,method,args ->
                when(method.name) {
                    "commit","apply" -> {
                        editor.commit()
                        if(!fail) { disk.clear();disk.putAll(memory.snapshot()) }
                        if(method.name=="commit") !fail else null
                    }
                    else -> { val v=method.invoke(editor,*(args?:emptyArray()));if(v is SharedPreferences.Editor) proxy else v }
                }
            } as SharedPreferences.Editor
        }
    }
    private class MemoryContext(val local:SharedPreferences):ContextWrapper(null) {
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=local
    }
    @Test fun creatingALocalPlaylistStillReturnsSuccessAfterDiskCommitFails() {
        val prefs=DiskPrefs();val context=MemoryContext(prefs)
        prefs.fail=true
        val created=LocalPlaylistRepository.createLocalOnly(context,"fixture",1000)
        assertEquals("fixture",created.name)
        assertEquals(1,LocalPlaylistStore.readPlaylists(context).size)
        val restarted=MemoryContext(DiskPrefs(prefs.disk))
        assertTrue(LocalPlaylistStore.readPlaylists(restarted).isEmpty())
        println("AUDIT local creation: commit=false still returns a created playlist and leaves optimistic memory; restart has no playlist")
    }
}
