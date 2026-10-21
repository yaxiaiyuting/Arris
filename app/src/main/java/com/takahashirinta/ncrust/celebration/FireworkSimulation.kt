package com.takahashirinta.ncrust.celebration

import kotlin.math.*
import kotlin.random.Random

/** Fixed pool; no per-frame particle allocation. Seconds and logical 400px-wide coordinates. */
class FireworkSimulation(private val height: Float, seed: Int = 812) {
    companion object { const val CAPACITY = 3000; const val DURATION = 8.9f }
    class Particle {
        var live = false; var x = 0f; var y = 0f; var px = 0f; var py = 0f
        var vx = 0f; var vy = 0f; var age = 0f; var life = 1f; var color = 0
        var spark = false; var willow = false; var emit = 0f; var radius = 7f
    }
    class Shell(val at: Float, val from: Float, val to: Float, val top: Float, val colors: IntArray, val willow: Boolean) {
        var launched = false; var exploded = false; var x = from * 400; var y = 0f; var px = x; var py = 0f
    }
    val particles = Array(CAPACITY) { Particle() }
    val shells = arrayOf(
        Shell(0f,.20f,.29f,.29f,intArrayOf(0,1,8),false), Shell(.66f,.81f,.72f,.38f,intArrayOf(2,3),false),
        Shell(1.38f,.44f,.48f,.22f,intArrayOf(4,5),true), Shell(2.05f,.15f,.24f,.43f,intArrayOf(6,0),false),
        Shell(2.72f,.86f,.76f,.32f,intArrayOf(0,3,8),false), Shell(3.38f,.37f,.39f,.26f,intArrayOf(0,2,6,1),false),
        Shell(3.74f,.68f,.64f,.38f,intArrayOf(4,5),true),
    )
    var elapsed = 0f; private set
    var activeCount = 0; private set
    var burstCount = 0; private set
    private var cursor = 0
    private val random = Random(seed)
    private fun r() = random.nextFloat()
    private fun emit(x: Float,y: Float,vx: Float,vy: Float,color: Int,life: Float,spark: Boolean,willow: Boolean = false) {
        if (activeCount == CAPACITY) return
        var attempts = 0
        while (particles[cursor].live && attempts++ < CAPACITY) cursor = (cursor + 1) % CAPACITY
        val p = particles[cursor]; p.live = true; p.x=x; p.px=x; p.y=y; p.py=y; p.vx=vx; p.vy=vy
        p.color=color; p.life=life; p.age=0f; p.spark=spark; p.willow=willow; p.emit=r()*.09f; p.radius=if(spark) 2.4f+r() else 7f+r()*3
        activeCount++; cursor=(cursor+1)%CAPACITY
    }
    private fun burst(s: Shell) {
        burstCount++
        val count = if (s.willow) 210 else 190
        for (i in 0 until count) {
            val z=1f-2f*(i+.5f)/count; val angle=i*2.3999632f+r()*.16f
            val spread=sqrt(1-z*z); val speed=(if(s.willow)132 else 154)*(.87f+r()*.25f)*(.84f+z*.17f)
            emit(s.x,s.y,cos(angle)*spread*speed,sin(angle)*spread*speed-10,s.colors[(i*s.colors.size/count).coerceAtMost(s.colors.lastIndex)],
                (if(s.willow)3.55f else 2.55f)*(.8f+r()*.35f),false,s.willow)
        }
        repeat(38) {val a=r()*2*PI.toFloat();val v=30+r()*40;emit(s.x,s.y,cos(a)*v,sin(a)*v-8,s.colors.last(),1.6f+r()*.6f,false)}
    }
    fun advance(seconds: Float) {
        if (!seconds.isFinite() || seconds <= 0 || elapsed >= DURATION) return
        var remaining=min(seconds,DURATION-elapsed)
        while(remaining>.00001f && elapsed<DURATION) {val dt=min(.025f,remaining);step(dt);remaining-=dt}
        if(elapsed>=DURATION-.0001f) {elapsed=DURATION;clear()}
    }
    private fun step(dt:Float) {
        elapsed+=dt
        for (s in shells) {
            if(elapsed < s.at || s.exploded) continue
            if(!s.launched) {s.launched=true;s.x=s.from*400;s.y=height+8;s.px=s.x;s.py=s.y}
            s.px=s.x;s.py=s.y
            val t=((elapsed-s.at)/1.1f).coerceIn(0f,1f)
            s.x=(s.from+(s.to-s.from)*t)*400+sin(t*10)*.7f
            s.y=height+8+(height*s.top-height-8)*(1-(1-t).pow(1.72f))
            repeat(2) {emit(s.x,s.y,(r()-.5f)*20,25+r()*55,4,.35f+r()*.5f,true)}
            if(t>=1) {s.exploded=true;burst(s)}
        }
        for (p in particles) {
            if(!p.live) continue
            p.age+=dt
            if(p.age>=p.life || p.y>height+30) {p.live=false;activeCount--;continue}
            p.px=p.x;p.py=p.y
            val drag=exp(-(if(p.spark)1.3f else if(p.willow).66f else .95f)*dt)
            p.vx*=drag;p.vy=p.vy*drag+(if(p.spark)24 else if(p.willow)31 else 42)*dt
            p.x+=p.vx*dt;p.y+=p.vy*dt;p.emit-=dt
            if(!p.spark && p.emit<=0 && p.age/p.life<.88f) {
                p.emit=if(p.willow).075f else .12f
                emit(p.x,p.y,p.vx*.13f+(r()-.5f)*9,p.vy*.13f+r()*6,if(p.willow)4 else p.color,
                    .35f+r()*(if(p.willow).7f else .3f),true)
            }
        }
        if(elapsed>=DURATION) clear()
    }
    fun clear() {for(p in particles)p.live=false;activeCount=0}
}
