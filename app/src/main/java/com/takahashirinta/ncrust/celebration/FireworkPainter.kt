package com.takahashirinta.ncrust.celebration

import android.graphics.*
import kotlin.math.*

/** Cached small glow sprites and a bounded low-resolution trail buffer; no app audio input. */
internal class FireworkPainter(private val height: Float) {
    val model = FireworkSimulation(height)
    private val colors = intArrayOf(0xffff589a.toInt(),0xffbc83ff.toInt(),0xff55d9ff.toInt(),0xff788bff.toInt(),0xffffd181.toInt(),0xffffe7ad.toInt(),0xff69eda6.toInt(),0xffff795f.toInt(),0xfff5b8ff.toInt())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND }
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val fade = Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val buffer = Bitmap.createBitmap(400,height.toInt().coerceIn(1,1600),Bitmap.Config.ARGB_8888)
    private val trail = Canvas(buffer)
    init { trail.scale(1f, buffer.height / height) }
    private val rect = RectF()
    private val sprites = colors.map { color ->
        Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888).also { bmp ->
            val glow=Paint(Paint.ANTI_ALIAS_FLAG)
            glow.shader=RadialGradient(20f,20f,20f,intArrayOf(0xfffffbe9.toInt(),0xfffff9e2.toInt(),color,(color and 0x00ffffff) or 0x52000000,color and 0x00ffffff),floatArrayOf(0f,.08f,.19f,.4f,1f),Shader.TileMode.CLAMP)
            Canvas(bmp).drawRect(0f,0f,40f,40f,glow)
        }
    }
    private fun glow(x:Float,y:Float,color:Int,size:Float,alpha:Float) {
        paint.alpha=(alpha.coerceIn(0f,1f)*255).toInt();rect.set(x-size/2,y-size/2,x+size/2,y+size/2)
        trail.drawBitmap(sprites[color],null,rect,paint)
    }
    fun draw(canvas:Canvas,dt:Float,alpha:Int=255) {
        model.advance(dt)
        fade.alpha=((1-.79f.pow(dt*60))*255).toInt().coerceIn(0,255)
        trail.drawPaint(fade);paint.xfermode=add
        for(s in model.shells) if(s.launched && !s.exploded) {
            paint.color=0xffffdb9a.toInt();paint.alpha=210;paint.strokeWidth=1.3f;trail.drawLine(s.px,s.py,s.x,s.y,paint);glow(s.x,s.y,5,13f,.95f)
        }
        for(p in model.particles) if(p.live) {
            val life=p.age/p.life
            var light=(1-life).pow(.65f)
            if(life>.65f) light*=.48f+.52f*abs(sin(p.x*.1f+p.age*21))
            val c=if(p.willow && life>.55f)4 else p.color
            paint.color=colors[c];paint.alpha=(light*(if(p.spark).34f else .58f)*255).toInt();paint.strokeWidth=if(p.spark).55f else 1.05f
            trail.drawLine(p.px,p.py,p.x,p.y,paint)
            glow(p.x,p.y,c,p.radius*(if(p.spark)1f else .75f+.25f*light),light*(if(p.spark).6f else 1f))
        }
        paint.xfermode=null;paint.alpha=alpha;paint.color=Color.WHITE
        rect.set(0f,0f,400f,height);canvas.drawBitmap(buffer,null,rect,paint)
    }
    fun release() {model.clear();buffer.recycle();sprites.forEach {it.recycle()} }
}
