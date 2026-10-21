package com.takahashirinta.ncrust.celebration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.takahashirinta.ncrust.ui.components.MetroDropdownRow
import com.takahashirinta.ncrust.ui.components.SettingActionRow
import com.takahashirinta.ncrust.ui.components.SettingSwitchRow
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun CelebrationSettingsRow(id:String) {
    val context=LocalContext.current
    val s=LocalStrings.current.celebration
    val options=rememberCelebrationOptions(context)
    var picker by remember {mutableStateOf(false)}
    var preview by remember {mutableStateOf(false)}
    when(id) {
        "action.birthday_date" -> {
            val date=if(BirthdayRule.valid(options.month,options.day)) {
                val cal=Calendar.getInstance().apply {set(2000,options.month-1,options.day)}
                SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(),"MMMMd"),Locale.getDefault()).format(cal.time)
            } else s.notSet
            SettingActionRow(label=s.dateTitle,trailing=date,onClick={picker=true})
            MetroText(s.dateHint,color=LocalMetroColors.current.onSurfaceVariant,modifier=Modifier.padding(horizontal=16.dp))
        }
        CelebrationPrefs.ENABLED -> SettingSwitchRow(title=s.enabledTitle,description=s.enabledHint,checked=options.enabled,onCheckedChange={if(it && !BirthdayRule.valid(options.month,options.day))picker=true else CelebrationPrefs.setEnabled(context,it)})
        CelebrationPrefs.ANIMATED -> SettingSwitchRow(title=s.animationTitle,description=s.animationHint,checked=options.animated,onCheckedChange={CelebrationPrefs.setAnimated(context,it)})
        "action.birthday_preview" -> SettingActionRow(label=s.previewTitle,trailing="",onClick={preview=true})
    }
    if(picker) BirthdayPicker(onClose={picker=false})
    if(preview)CelebrationOverlay(CelebrationKind.BIRTHDAY,onClose={preview=false})
}

@Composable
private fun BirthdayPicker(onClose:()->Unit) {
    val context=LocalContext.current
    val s=LocalStrings.current.celebration
    val original=remember {CelebrationPrefs.read(context)}
    var month by remember {mutableIntStateOf(if(BirthdayRule.valid(original.month,original.day))original.month else Calendar.getInstance().get(Calendar.MONTH)+1)}
    var day by remember {mutableIntStateOf(if(BirthdayRule.valid(original.month,original.day))original.day else 1)}
    Dialog(onDismissRequest=onClose) {
        Column(Modifier.fillMaxWidth().background(LocalMetroColors.current.background).verticalScroll(rememberScrollState()).padding(20.dp)) {
            MetroText(s.dateTitle,color=LocalMetroColors.current.onBackground)
            Spacer(Modifier.height(12.dp))
            MetroText(s.dateHint,color=LocalMetroColors.current.onSurfaceVariant)
            MetroDropdownRow(label=s.monthLabel,selectedIndex=month-1,options=(1..12).map {it.toString()},onSelect={month=it+1;day=day.coerceAtMost(BirthdayRule.days(month))})
            MetroDropdownRow(label=s.dayLabel,selectedIndex=day-1,options=(1..BirthdayRule.days(month)).map {it.toString()},onSelect={day=it+1})
            CelebrationButton(s.save,{CelebrationPrefs.setDate(context,month,day);onClose()})
            if(BirthdayRule.valid(original.month,original.day))CelebrationButton(s.clear,{CelebrationPrefs.clearDate(context);onClose()},primary=false,secondaryColor=LocalMetroColors.current.onBackground)
            CelebrationButton(s.cancel,onClose,primary=false,secondaryColor=LocalMetroColors.current.onBackground)
        }
    }
}
