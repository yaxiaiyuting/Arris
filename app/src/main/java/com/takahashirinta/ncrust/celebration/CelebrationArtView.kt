package com.takahashirinta.ncrust.celebration

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import com.takahashirinta.ncrust.R
import kotlin.math.*

/** The vsync clock and all changing geometry stay in this leaf View, outside Compose state. */
internal class CelebrationArtView(context: Context) : View(context) {
    private val carry=drawable(R.drawable.tux_carry)
    private val rest=drawable(R.drawable.tux_rest)
    private val light=drawable(R.drawable.tux_light)
    private val listener=drawable(R.drawable.tux_listener)
    private val cake=drawable(R.drawable.birthday_cake)
    private fun drawable(id:Int)=ContextCompat.getDrawable(context,id)!!.mutate()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val flame=PathParser.createPathFromPathData("M0 0C-13-6-4-17 0-28C12-14 11-5 0 0Z")!!
    private val core=PathParser.createPathFromPathData("M0-2C-7-6-2-12 0-16C5-10 6-5 0-2Z")!!
    private val arm=PathParser.createPathFromPathData("M206 144Q247 153 320 123Q336 119 339 134Q315 161 258 177Q222 188 202 167Z")!!
    private val smoke=PathParser.createPathFromPathData("M0 0c-11-9 12-17 1-28s-7-16-1-22")!!
    private val smokeMeasure=PathMeasure(smoke,false)
    private val smokeSegment=Path()
    private val orange=0xffffb066.toInt()
    private val cone=RadialGradient(0f,0f,128f,intArrayOf(0x8fffb066.toInt(),0x33ffc37d,0x00ffb066),floatArrayOf(0f,.42f,1f),Shader.TileMode.CLAMP)
    private val bloom=RadialGradient(0f,0f,34f,intArrayOf(0xafffb066.toInt(),0x00ffb066),null,Shader.TileMode.CLAMP)
    private val floor=RadialGradient(0f,0f,1f,intArrayOf(0x8cffd199.toInt(),0x2bffb066,0x00ffb066),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
    private val shade=RadialGradient(0f,0f,1f,intArrayOf(0x00000000,0x1a000000,0xb8000000.toInt(),0xf5000000.toInt()),floatArrayOf(0f,.24f,.66f,1f),Shader.TileMode.CLAMP)
    private var background:Shader?=null
    private var stage=CelebrationPhase.ARRIVING
    private var kind=CelebrationKind.BIRTHDAY
    private var animate=true
    private var stageAt=SystemClock.uptimeMillis()
    private var lastFrame=stageAt
    private var fire:FireworkPainter?=null
    private var logicalHeight=740f
    private var failed=false
    private val scene=RectF()
    fun placeScene(x:Float,y:Float,w:Float,h:Float) {
        if(w<=0f || h<=0f || !x.isFinite() || !y.isFinite()) return
        scene.set(x,y,x+w,y+h)
        invalidate()
    }
    var onDrawingFailure:(()->Unit)?=null
    fun configure(next:CelebrationPhase, birthdayKind:CelebrationKind, animated:Boolean) {
        if(stage!=next || kind!=birthdayKind || animate!=animated) {stageAt=SystemClock.uptimeMillis();lastFrame=stageAt}
        stage=next;kind=birthdayKind;animate=animated
        if(!animated || next==CelebrationPhase.DONE) {fire?.release();fire=null}
        invalidate()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        val scale=min(w/400f,h/600f).coerceAtLeast(.1f)
        logicalHeight=h/scale
        background=RadialGradient(200f,logicalHeight*.44f,max(400f,logicalHeight*.7f),intArrayOf(0xff1c4a35.toInt(),0xff102c1e.toInt(),0xff08160f.toInt()),floatArrayOf(0f,.49f,1f),Shader.TileMode.CLAMP)
        fire?.release();fire=null
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(failed) {canvas.drawColor(0xff08160f.toInt());return}
        try {drawScene(canvas)} catch(error:RuntimeException) {
            failed=true;fire?.release();fire=null
            Log.w("Celebration", "Drawing stopped: ${error.javaClass.simpleName}")
            post {onDrawingFailure?.invoke()}
        }
    }
    private fun drawScene(canvas:Canvas) {
        val now=SystemClock.uptimeMillis();val seconds=(now-stageAt)/1000f
        val dt=((now-lastFrame)/1000f).coerceAtLeast(.001f);lastFrame=now
        val scale=min(width/400f,height/600f).coerceAtLeast(.1f)
        canvas.drawColor(0xff08160f.toInt());canvas.save();canvas.translate((width-400*scale)/2,0f);canvas.scale(scale,scale)
        val h=logicalHeight;paint.shader=background;paint.alpha=255;canvas.drawRect(-2000f,0f,2400f,h,paint);paint.shader=null
        // v3.6.3：首帧**还没有量到舞台**时只画底色，不画插画。
        // 旧代码在这里用「屏幕居中 400×360」兜底，第一次 onGloballyPositioned 之后
        // 整幅画瞬移到标题与按钮之间 —— 用户读到的就是「打开时错位/跳一下」。
        // 舞台由 Compose 在同一个首帧布局里写入，最迟下一帧就会 invalidate。
        if(scene.isEmpty || scene.width()<=0f || scene.height()<=0f) {canvas.restore();return}
        // Fit the 400 x 360 drawing into the measured space between heading and actions.
        val left=scene.left/scale-(width/scale-400f)/2
        val top=scene.top/scale
        val sceneWidth=scene.width()/scale
        val sceneHeight=scene.height()/scale
        val artScale=min(sceneWidth/400f,sceneHeight/360f)
        val artX=left+(sceneWidth-400*artScale)/2
        val artY=top+(sceneHeight-360*artScale)/2
        val waiting=stage==CelebrationPhase.WAITING
        val lit=stage==CelebrationPhase.LIGHTING || waiting
        canvas.save();canvas.translate(artX,artY);canvas.scale(artScale,artScale)
        if(kind==CelebrationKind.LISTENER) {
            drawTux(canvas,listener,88f,60f,.8f,if(animate)sin(seconds*.7f)*1.4f else 0f)
        } else {
            drawBirthday(canvas,0f,seconds,lit)
        }
        canvas.restore()
        if(kind==CelebrationKind.BIRTHDAY && lit) {paint.shader=shade;paint.alpha=if(stage==CelebrationPhase.LIGHTING)(255*(seconds/1.15f).coerceIn(0f,1f)).toInt() else 255
            canvas.save();canvas.translate(artX+274*artScale,artY+196*artScale);canvas.scale(310*artScale,205*artScale);canvas.drawRect(-20f,-20f,20f,20f,paint);canvas.restore();paint.shader=null;paint.alpha=255}
        if(stage==CelebrationPhase.EXTINGUISHING) {paint.color=Color.BLACK;paint.alpha=(seconds/1.1f*170).toInt().coerceIn(0,170);canvas.drawRect(-2000f,0f,2400f,h,paint);paint.alpha=255}
        if(stage==CelebrationPhase.BLACKOUT || stage==CelebrationPhase.FIREWORKS || stage==CelebrationPhase.BLESSING) {
            val opacity=when(stage) {CelebrationPhase.BLACKOUT->(seconds/.45f).coerceIn(0f,1f);CelebrationPhase.BLESSING->(1-seconds/1.7f).coerceIn(0f,1f);else->1f}
            paint.color=Color.BLACK;paint.alpha=(opacity*255).toInt();canvas.drawRect(-2000f,0f,2400f,h,paint);paint.alpha=255
            if(stage!=CelebrationPhase.BLACKOUT && animate) {
                if(fire==null) fire=FireworkPainter(h)
                fire?.draw(canvas,dt,(opacity*255).toInt())
            }
        }
        if(stage==CelebrationPhase.DONE || stage==CelebrationPhase.BLESSING) {
            val breath=if(animate)(.35f+.15f*sin(seconds*.7f)) else .4f
            paint.shader=cone;paint.alpha=(breath*120).toInt();canvas.save();canvas.translate(200f,h*.8f);canvas.scale(1.3f,.4f);canvas.drawCircle(0f,0f,128f,paint);canvas.restore();paint.shader=null;paint.alpha=255
        }
        canvas.restore()
        if(animate && isAttachedToWindow && windowVisibility==VISIBLE) postInvalidateOnAnimation()
    }
    private fun drawTux(c:Canvas,d:Drawable,x:Float,y:Float,s:Float,bob:Float=0f) {
        c.save();c.translate(x,y+bob);c.scale(s,s);d.setBounds(0,-70,360,310);d.draw(c);c.restore()
    }
    private fun drawBirthday(c:Canvas,y:Float,t:Float,lit:Boolean) {
        val a=when(stage) {CelebrationPhase.ARRIVING->(t/(CelebrationTiming.ARRIVE/1000f)).coerceIn(0f,1f);else->1f}
        val arrive=1-(1-a).pow(3)
        val placing=if(stage==CelebrationPhase.PLACING)(t/(CelebrationTiming.PLACE/1000f)).coerceIn(0f,1f) else if(stage==CelebrationPhase.ARRIVING)0f else 1f
        val tx=23f-263*(1-arrive)
        val bob=if(!animate)0f else when(stage) {CelebrationPhase.ARRIVING->sin(t*15)*2.5f;CelebrationPhase.WAITING->sin(t*.96f)*1.5f;CelebrationPhase.BLESSING->if(t<2.2f)-abs(sin(t*PI.toFloat()/1.1f))*4 else 0f;else->0f}
        val pose=when(stage) {CelebrationPhase.ARRIVING,CelebrationPhase.PLACING->carry;CelebrationPhase.LIGHTING->light;else->rest}
        drawTux(c,pose,tx,y+110,.64f,bob)
        if(stage==CelebrationPhase.LIGHTING) {
            c.save();c.translate(23f,y+110);c.scale(.64f,.64f);c.scale(1+(t/1.1f).coerceIn(0f,1f)*.5f,1f,201f,147f)
            paint.color=0xff1c2a24.toInt();c.drawPath(arm,paint);paint.color=0xffe9b64d.toInt();paint.strokeWidth=5f;c.drawLine(339f,134f,347f,113f,paint)
            c.save();c.translate(347f,113f);c.scale(.5f,.5f);c.drawPath(flame,paint);c.restore();c.restore()
        }
        val cx=236+38*placing-263*(1-arrive);val cy=y+228+59*placing
        if(lit) {
            paint.shader=cone;paint.alpha=230;c.save();c.translate(cx,cy-91);c.drawCircle(0f,0f,128f,paint);c.restore()
            paint.shader=floor;paint.alpha=200;c.save();c.translate(258f,y+294);c.scale(137f,27f);c.drawCircle(0f,0f,1f,paint);c.restore();paint.shader=null;paint.alpha=255
        }
        c.save();c.translate(cx,cy);cake.setBounds(-80,-115,80,15);cake.draw(c)
        for(i in 0..2) {
            val x=(i-1)*26.5f;val base=if(i==1)-91f else -86f
            val ignited=stage!=CelebrationPhase.LIGHTING || t>=.25f+i*.4f
            if((lit && ignited) || stage==CelebrationPhase.EXTINGUISHING && t<.4f) {
                val shrink=if(stage==CelebrationPhase.EXTINGUISHING)(1-t/.4f).coerceIn(0f,1f) else 1f
                c.save();c.translate(x,base);if(animate)c.rotate(sin(t*(1.5f+i*.12f)+i)*2.5f);c.scale(shrink,shrink)
                paint.shader=bloom;c.drawCircle(0f,-14f,34f,paint);paint.shader=null;paint.color=orange;c.drawPath(flame,paint);paint.color=0xfffff3c4.toInt();c.drawPath(core,paint);c.restore()
            }
            if(stage==CelebrationPhase.EXTINGUISHING && t<1.1f) {
                smokeSegment.rewind();smokeMeasure.getSegment(0f,smokeMeasure.length*(t/.8f).coerceIn(0f,1f),smokeSegment,true)
                c.save();c.translate(x+t*5,base-t*24);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f;paint.color=0xffc8d8c6.toInt();paint.alpha=((1-t/1.1f)*190).toInt().coerceIn(0,190);c.drawPath(smokeSegment,paint);paint.alpha=255;paint.style=Paint.Style.FILL;c.restore()
            }
        }
        c.restore()
    }
    override fun onDetachedFromWindow() {fire?.release();fire=null;onDrawingFailure=null;super.onDetachedFromWindow()}
}
