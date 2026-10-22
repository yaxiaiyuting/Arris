package com.takahashirinta.ncrust.celebration

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.takahashirinta.ncrust.ui.theme.AppShapes
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

@Composable
internal fun rememberMotionAllowed(): Boolean {
    val context=LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val p=CelebrationPrefs.prefs(context)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange:Boolean) {tick++}}
        p.registerOnSharedPreferenceChangeListener(listener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose {p.unregisterOnSharedPreferenceChangeListener(listener);context.contentResolver.unregisterContentObserver(observer)}
    }
    return remember(context,tick) {
        MotionPrefs.readUiMotionEnabled(CelebrationPrefs.prefs(context)) &&
            runCatching {Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0}.getOrDefault(false)
    }
}

/** Fullscreen native layer. Closing/backgrounding removes both View clock and AudioRecord. */
@Composable
internal fun NativeCelebrationOverlay(kind:CelebrationKind,onClose:()->Unit,onVisible:()->Unit = {}) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val s=LocalStrings.current.celebration
    val options=rememberCelebrationOptions(context)
    val motion=rememberMotionAllowed()
    var failed by remember {mutableStateOf(false)}
    val animated=options.animated && motion && !failed
    var phase by remember {mutableStateOf(if(kind==CelebrationKind.LISTENER)CelebrationPhase.DONE else if(animated)CelebrationPhase.ARRIVING else CelebrationPhase.WAITING)}
    var micRequested by remember {mutableStateOf(false)}
    var micArmed by remember {mutableStateOf(false)}
    var micFeedback by remember {mutableStateOf("")}
    var active by remember {mutableStateOf(true)}
    val close by rememberUpdatedState(onClose)
    val visible by rememberUpdatedState(onVisible)
    val placement=remember {CelebrationPlacement()}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        if(active && phase==CelebrationPhase.WAITING) {
            micRequested=granted
            if(!granted)micFeedback=s.micDenied
        }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP) {active=false;micRequested=false;close()} }
        lifecycle.addObserver(observer)
        onDispose {active=false;lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(Unit) {withFrameNanos {};if(active && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))visible()}
    LaunchedEffect(animated) {
        if(!animated) phase=if(kind==CelebrationKind.LISTENER || phase.ordinal>=CelebrationPhase.EXTINGUISHING.ordinal)CelebrationPhase.DONE else CelebrationPhase.WAITING
    }
    LaunchedEffect(phase,animated) {
        if(!animated)return@LaunchedEffect
        val next=when(phase) {
            CelebrationPhase.ARRIVING -> CelebrationTiming.ARRIVE to CelebrationPhase.PLACING
            CelebrationPhase.PLACING -> CelebrationTiming.PLACE to CelebrationPhase.LIGHTING
            CelebrationPhase.LIGHTING -> CelebrationTiming.LIGHT to CelebrationPhase.WAITING
            CelebrationPhase.EXTINGUISHING -> CelebrationTiming.EXTINGUISH to CelebrationPhase.BLACKOUT
            CelebrationPhase.BLACKOUT -> CelebrationTiming.BLACKOUT to CelebrationPhase.FIREWORKS
            CelebrationPhase.FIREWORKS -> CelebrationTiming.SHOW to CelebrationPhase.BLESSING
            CelebrationPhase.BLESSING -> CelebrationTiming.REVEAL to CelebrationPhase.DONE
            else -> null
        }
        next?.let {delay(it.first);phase=it.second}
    }
    LaunchedEffect(micRequested,phase) {
        if(!micRequested || phase!=CelebrationPhase.WAITING)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            micFeedback="";micArmed=false
            val result=BlowRecorder.listen(context) {micArmed=true}
            micRequested=false
            if(active && phase==CelebrationPhase.WAITING) when(result) {
                BlowResult.DETECTED -> phase=if(animated)CelebrationPhase.EXTINGUISHING else CelebrationPhase.DONE
                BlowResult.TIMEOUT -> micFeedback=s.micTimeout
                BlowResult.UNAVAILABLE -> micFeedback=s.micUnavailable
            }
        }
    }
    Dialog(onDismissRequest={micRequested=false;close()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().background(Color(0xff08160f))) {
            AndroidView(factory={CelebrationArtView(it).apply {importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO;placement.renderer=this}},
                modifier=Modifier.fillMaxSize().onGloballyPositioned {val p=it.positionInRoot();placement.root(p.x,p.y)}, update={view ->
                    view.onDrawingFailure={failed=true}
                    view.configure(phase,kind,animated)
                })
            CelebrationText(kind,phase,animated,micRequested,micArmed,micFeedback,placement,
                onClose={micRequested=false;close()},
                onMic={
                    if(!micRequested) {
                        micFeedback=""
                        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)micRequested=true
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, onBlow={micRequested=false;phase=if(animated)CelebrationPhase.EXTINGUISHING else CelebrationPhase.DONE},
                onReplay={micRequested=false;micFeedback="";phase=if(animated)CelebrationPhase.ARRIVING else CelebrationPhase.WAITING})
        }
    }
}

@Composable
private fun CelebrationText(kind:CelebrationKind,phase:CelebrationPhase,animated:Boolean,mic:Boolean,armed:Boolean,feedback:String,
    placement:CelebrationPlacement,
    onClose:()->Unit,onMic:()->Unit,onBlow:()->Unit,onReplay:()->Unit) {
    val s=LocalStrings.current.celebration
    val night=phase==CelebrationPhase.BLACKOUT || phase==CelebrationPhase.FIREWORKS
    val candle=phase==CelebrationPhase.LIGHTING || phase==CelebrationPhase.WAITING || phase==CelebrationPhase.EXTINGUISHING
    val done=phase==CelebrationPhase.DONE
    val wishes=phase==CelebrationPhase.BLESSING || done
    val ink=Color(0xfffff3df)
    // v3.6.3：footer 的预留高度按阶段给，CelebrationFrame 用 320ms 动画过渡。
    // 旧版 footer 从 12dp 直接涨到 152~176dp，插画在同一个 layout pass 里被挤小 43%
    // —— 用户看到的是「Tux 错位/跳了一下」。数字来源见 CelebrationLayout 的 KDoc。
    val footerReserve=when {
        kind==CelebrationKind.LISTENER -> if(done)88.dp else 12.dp
        phase==CelebrationPhase.WAITING -> 200.dp
        wishes -> 216.dp
        else -> 12.dp
    }
    CelebrationFrame(placement,heading={
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                MetroText(if(night)"" else "ARRIS",color=ink.copy(alpha=if(candle).1f else .55f),style=TextStyle(fontSize=12.sp,letterSpacing=2.sp))
                CelebrationButton(s.close,onClose,primary=false)
            }
            if(!night) {
                Column(Modifier.fillMaxWidth().heightIn(min=105.dp).padding(top=16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    MetroText(if(kind==CelebrationKind.LISTENER)s.eggTitle else s.titles[phase.ordinal],color=ink.copy(alpha=if(candle).16f else 1f),
                        style=TextStyle(fontSize=25.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center))
                    Spacer(Modifier.height(10.dp))
                    MetroText(if(kind==CelebrationKind.LISTENER)s.eggCaption else s.captions[phase.ordinal],color=Color(0xffb4c6b7).copy(alpha=if(candle).15f else 1f),style=TextStyle(fontSize=13.sp,textAlign=TextAlign.Center))
                }
            } else Spacer(Modifier.height(105.dp))
        }
    },footer={
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
            if(!night) {
                if(wishes && kind==CelebrationKind.BIRTHDAY) BlessingLines(phase,animated)
                if(phase==CelebrationPhase.WAITING) {
                    MetroText(s.micHint,color=Color(0xffb4c6b7),style=TextStyle(fontSize=12.sp,textAlign=TextAlign.Center))
                    if(feedback.isNotEmpty())MetroText(feedback,color=Color(0xffffd09b),style=TextStyle(fontSize=12.sp,textAlign=TextAlign.Center))
                    Spacer(Modifier.height(12.dp))
                    CelebrationButton(if(mic)if(armed)s.micListening else s.micCalibrating else s.micStart,onMic,enabled=!mic,candle=true)
                    Spacer(Modifier.height(8.dp))
                    CelebrationButton(s.manualBlow,onBlow,primary=false)
                } else if(done) {
                    Spacer(Modifier.height(18.dp));CelebrationButton(s.continueListening,onClose)
                    if(kind==CelebrationKind.BIRTHDAY)CelebrationButton(s.replay,onReplay,primary=false)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }, footerReserve=footerReserve)
}

@Composable
private fun BlessingLines(phase:CelebrationPhase,animated:Boolean) {
    val s=LocalStrings.current.celebration
    s.wishes.forEachIndexed {index,text ->
        val alpha=remember {Animatable(if(!animated || phase==CelebrationPhase.DONE)1f else 0f)}
        LaunchedEffect(phase,animated) {if(animated && phase==CelebrationPhase.BLESSING) {alpha.snapTo(0f);delay(index*800L);alpha.animateTo(1f,SokuouTweens.CoverFade)} else alpha.snapTo(1f)}
        MetroText(text,color=Color(if(index==2)0xffffe7c2 else 0xffb4c6b7),style=TextStyle(fontSize=if(index==2)17.sp else 13.sp,fontWeight=if(index==2)FontWeight.SemiBold else FontWeight.Normal,textAlign=TextAlign.Center),
            modifier=Modifier.fillMaxWidth().padding(top=if(index==2)12.dp else 3.dp).graphicsLayer {this.alpha=alpha.value;translationY=(1-alpha.value)*7})
    }
}

@Composable
internal fun CelebrationButton(text:String,onClick:()->Unit,primary:Boolean=true,enabled:Boolean=true,candle:Boolean=false,secondaryColor:Color=Color(0xffc4ccb9)) {
    val colors=if(primary && candle)listOf(Color(0xff211b10),Color(0xff332518)) else if(primary) listOf(Color(0xffffe8c7),Color(0xfff5c98e)) else listOf(Color.Transparent,Color.Transparent)
    Box(Modifier.then(if(primary)Modifier.fillMaxWidth() else Modifier).heightIn(min=48.dp)
        .then(if(candle)Modifier.border(1.dp,Color(0x40c5a26a),AppShapes.medium) else Modifier)
        .background(Brush.linearGradient(colors),AppShapes.medium).clickable(enabled=enabled,role=Role.Button,onClick=onClick)
        .padding(horizontal=16.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
        MetroText(text,color=if(primary && candle)Color(0xffe8cfa9) else if(primary)Color(0xff303626) else secondaryColor,style=TextStyle(fontSize=14.sp,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center))
    }
}
