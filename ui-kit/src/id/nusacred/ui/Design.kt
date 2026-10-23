package id.nusacred.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Ink: Color @Composable get() = LocalEntity.current.ink
    val Muted: Color @Composable get() = LocalEntity.current.muted
    val Teal: Color @Composable get() = LocalEntity.current.accent
    val TealSoft: Color @Composable get() = LocalEntity.current.soft
    val Paper: Color @Composable get() = LocalEntity.current.paper
    val Line: Color @Composable get() = LocalEntity.current.line
    val White=Color.White
    val Amber=Color(0xFF98602B)
    val AmberSoft=Color(0xFFFBF0DE)
    val Red=Color(0xFFAE4141)
    val RedSoft=Color(0xFFFAEBE8)
}
@Composable fun Label(text: String, size: Int=15, color: Color=Palette.Ink, weight: FontWeight=FontWeight.Normal,
                      modifier: Modifier=Modifier, lines: Int=Int.MAX_VALUE) {
    BasicText(text,modifier,style=TextStyle(color=color,fontSize=size.sp,lineHeight=(size*1.45).sp,
        fontFamily=FontFamily.SansSerif,fontWeight=weight),maxLines=lines,overflow=TextOverflow.Ellipsis)
}
@Composable fun Heading(text: String, size: Int=30, modifier: Modifier=Modifier) =
    Label(text,size,weight=FontWeight.SemiBold,modifier=modifier.semantics { heading() })
@Composable fun Eyebrow(text: String, color: Color=Palette.Muted) = Label(text.uppercase(),11,color,FontWeight.SemiBold)
@Composable fun Brand(light: Boolean=false, compact: Boolean=false) {
    val entity=LocalEntity.current
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if(light) Color.White.copy(alpha=0.14f) else entity.accent),contentAlignment=Alignment.Center) {
            Icon(entity.icon,Color.White,Modifier.size(22.dp))
        }
        if(!compact) Column(verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Label(entity.brand,19,if(light) Color.White else entity.ink,FontWeight.Bold)
            Label(entity.role.uppercase(),9,if(light) Color.White.copy(alpha=0.72f) else entity.muted,FontWeight.SemiBold)
        }
    }
}
@Composable fun Icon(name: String, color: Color=Palette.Ink, modifier: Modifier=Modifier, description: String?=null) {
    Canvas(modifier.size(22.dp).then(if(description!=null) Modifier.semantics { contentDescription=description } else Modifier)) {
        val scale=size.width/24f
        fun line(x:Float,y:Float,a:Float,b:Float) = drawLine(color,Offset(x*scale,y*scale),Offset(a*scale,b*scale),1.7f*scale,StrokeCap.Round)
        fun path(vararg p:Pair<Float,Float>,close:Boolean=false) {
            val shape=Path();p.forEachIndexed { i,v -> if(i==0) shape.moveTo(v.first*scale,v.second*scale) else shape.lineTo(v.first*scale,v.second*scale) }
            if(close) shape.close();drawPath(shape,color,style=Stroke(1.7f*scale,cap=StrokeCap.Round))
        }
        when(name) {
            "mark","check" -> { line(5f,12f,10f,17f);line(10f,17f,20f,6f);if(name=="mark") line(5f,6f,5f,9f) }
            "arrow","chevron" -> { line(5f,12f,19f,12f);line(14f,7f,19f,12f);line(19f,12f,14f,17f) }
            "plus" -> { line(5f,12f,19f,12f);line(12f,5f,12f,19f) }
            "close" -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
            "scan" -> { for((x,y) in listOf(4f to 4f,20f to 4f,4f to 20f,20f to 20f)) {
                line(x,y,x+(if(x==4f) 5 else -5),y);line(x,y,x,y+(if(y==4f) 5 else -5)) };line(7f,12f,17f,12f) }
            "shield" -> { path(12f to 3f,20f to 6f,19f to 15f,12f to 21f,5f to 15f,4f to 6f,close=true);line(8f,11f,11f,14f);line(11f,14f,16f,9f) }
            "wallet" -> { path(4f to 5f,18f to 5f,18f to 9f,21f to 9f,21f to 17f,18f to 17f,18f to 20f,4f to 20f,close=true);line(4f,9f,18f,9f);line(16f,13f,21f,13f) }
            "briefcase" -> { path(3f to 7f,21f to 7f,21f to 20f,3f to 20f,close=true);path(8f to 7f,8f to 3f,16f to 3f,16f to 7f);line(3f,12f,21f,12f);line(11f,11f,13f,11f) }
            "document" -> { path(5f to 3f,14f to 3f,19f to 8f,19f to 21f,5f to 21f,close=true);path(14f to 3f,14f to 8f,19f to 8f);line(9f,12f,15f,12f);line(9f,16f,15f,16f) }
            "home" -> { path(3f to 11f,12f to 3f,21f to 11f);path(6f to 10f,6f to 21f,18f to 21f,18f to 10f);path(10f to 21f,10f to 15f,14f to 15f,14f to 21f) }
            "logout" -> { path(10f to 4f,4f to 4f,4f to 20f,10f to 20f);line(10f,12f,21f,12f);line(17f,8f,21f,12f);line(21f,12f,17f,16f) }
            "clock" -> { drawCircle(color,9f*scale,style=Stroke(1.7f*scale));line(12f,6f,12f,12f);line(12f,12f,16f,14f) }
            "refresh" -> { drawArc(color,45f,285f,false,Offset(4f*scale,4f*scale),Size(16f*scale,16f*scale),style=Stroke(1.7f*scale));path(17f to 3f,20f to 7f,15f to 7f) }
            else -> { drawCircle(color,8f*scale,style=Stroke(1.7f*scale));drawCircle(color,2f*scale) }
        }
    }
}
@Composable fun Action(text: String, modifier: Modifier=Modifier, primary: Boolean=true, enabled: Boolean=true,
                       icon: String?=null, onClick: () -> Unit) {
    val background=if(primary) Palette.Teal else Palette.White
    val foreground=if(primary) Palette.White else Palette.Ink
    Row(modifier.heightIn(min=48.dp).clip(RoundedCornerShape(10.dp)).background(if(enabled) background else Palette.Line)
        .border(1.dp,if(primary) Color.Transparent else Palette.Line,RoundedCornerShape(10.dp))
        .clickable(enabled=enabled,role=Role.Button,onClick={ if(enabled) onClick() }).padding(horizontal=18.dp,vertical=12.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
        if(icon!=null) Icon(icon,if(enabled) foreground else Palette.Muted,Modifier.size(19.dp))
        Label(text,14,if(enabled) foreground else Palette.Muted,FontWeight.SemiBold)
    }
}
@Composable fun Card(modifier: Modifier=Modifier, background: Color=Palette.White, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(background).border(1.dp,Palette.Line,RoundedCornerShape(14.dp))
        .padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
}
@Composable fun Field(label: String,value: String,onChange:(String)->Unit,modifier:Modifier=Modifier,hint:String="",enabled:Boolean=true) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Label(label,13,weight=FontWeight.Medium)
        BasicTextField(value,onValueChange={ if(it.length<=4096) onChange(it) },singleLine=true,enabled=enabled,
            textStyle=TextStyle(color=Palette.Ink,fontSize=15.sp,fontFamily=FontFamily.SansSerif),cursorBrush=SolidColor(Palette.Teal),
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).onFocusChanged { focused=it.isFocused }
                .border(if(focused) 2.dp else 1.dp,if(focused) Palette.Teal else Palette.Line,RoundedCornerShape(9.dp))
                .background(Palette.White,RoundedCornerShape(9.dp)).padding(14.dp).semantics { contentDescription=label },
            decorationBox={ inner -> Box { if(value.isEmpty()) Label(hint,15,Palette.Muted);inner() } })
    }
}
@Composable fun Pill(text: String, tone: String="neutral") {
    val (ink,bg)=when(tone) { "success" -> Palette.Teal to Palette.TealSoft;"warning" -> Palette.Amber to Palette.AmberSoft;"error" -> Palette.Red to Palette.RedSoft;else -> Palette.Muted to Palette.Paper }
    Row(Modifier.background(bg,RoundedCornerShape(6.dp)).padding(horizontal=9.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(ink,RoundedCornerShape(3.dp)));Label(text,11,ink,FontWeight.Medium)
    }
}
@Composable fun Notice(text: String,error: Boolean=false) {
    Row(Modifier.fillMaxWidth().background(if(error) Palette.RedSoft else Palette.TealSoft,RoundedCornerShape(10.dp)).padding(15.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
        Icon(if(error) "clock" else "shield",if(error) Palette.Red else Palette.Teal,Modifier.size(19.dp))
        Label(text,13,if(error) Palette.Red else Palette.Ink)
    }
}
@Composable fun KeyValue(label:String,value:String) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) { Label(label,12,Palette.Muted);Label(value,15,weight=FontWeight.Medium) }
}
@Composable fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
@Composable fun EmptyState(title:String,description:String,icon:String="document",action:(@Composable ()->Unit)?=null) {
    Column(Modifier.fillMaxWidth().padding(vertical=30.dp,horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(58.dp).background(Palette.TealSoft,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) { Icon(icon,Palette.Teal,Modifier.size(28.dp)) }
        Label(title,18,weight=FontWeight.SemiBold);Label(description,14,Palette.Muted);action?.invoke()
    }
}
@Composable fun QrCode(modules:List<String>,modifier:Modifier=Modifier) {
    Canvas(modifier.background(Color.White).padding(16.dp).semantics { contentDescription="QR code. Open the same request using the wallet link below." }) {
        val n=modules.size; if(n==0) return@Canvas
        val step=size.minDimension/(n+8);val left=(size.width-step*(n+8))/2;val top=(size.height-step*(n+8))/2
        modules.forEachIndexed { y,row -> row.forEachIndexed { x,c -> if(c=='1') drawRect(Color.Black,Offset(left+(x+4)*step,top+(y+4)*step),Size(step+0.2f,step+0.2f)) } }
    }
}
