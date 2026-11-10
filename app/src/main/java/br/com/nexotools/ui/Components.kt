package br.com.nexotools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*
import br.com.nexotools.domain.AngleDisplay

@Composable fun Glyph(id:Int,description:String?=null,modifier:Modifier=Modifier) {
    Icon(painterResource(id),description,modifier.size(Dimensions.glyph))
}
@Composable fun Panel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(Dimensions.panelRadius),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Space.lg),verticalArrangement=Arrangement.spacedBy(Space.md),content=content)
    }
}
@Composable fun Hint(text:String) { Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Metric(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.md),verticalAlignment=Alignment.Top) {
        Text(label,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
        Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,textAlign=androidx.compose.ui.text.style.TextAlign.End)
    }
}
@Composable fun InstrumentReading(text:String,modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.primary) {
    val measurer=rememberTextMeasurer(cacheSize=32)
    val density=LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        val available=with(density){maxWidth.toPx()}
        val fontSize=remember(text,available,density.density,density.fontScale) {
            val full=measurer.measure(AnnotatedString(text),style=InstrumentStyle,softWrap=false,maxLines=1)
            if(full.size.width<=available)return@remember InstrumentStyle.fontSize
            var low=8f
            var high=InstrumentStyle.fontSize.value
            repeat(8) {
                val size=(low+high)/2
                val width=measurer.measure(AnnotatedString(text),style=InstrumentStyle.copy(fontSize=size.sp),softWrap=false,maxLines=1).size.width
                if(width<=available)low=size else high=size
            }
            low.sp
        }
        Text(text,style=InstrumentStyle.copy(fontSize=fontSize),color=color,maxLines=1,softWrap=false)
    }
}
@Composable fun SectionLabel(text:String) { Text(text,style=MaterialTheme.typography.titleMedium) }
@Composable fun Trace(values:List<Double>,modifier:Modifier=Modifier,range:ClosedFloatingPointRange<Double>?=null) {
    val accent=MaterialTheme.colorScheme.primary; val grid=MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(Dimensions.traceHeight).semantics{contentDescription="Gráfico das últimas ${values.size} leituras"}) {
        repeat(4){ val y=size.height*it/3; drawLine(grid,Offset(0f,y),Offset(size.width,y),1f) }
        if(values.size>1){ val lo=range?.start?:values.min(); val hi=range?.endInclusive?:values.max(); val span=max(hi-lo,1.0)
            val path=Path(); values.forEachIndexed { i,v-> val x=size.width*i/(values.size-1); val y=(size.height*(1-(v-lo)/span)).toFloat().coerceIn(0f,size.height); if(i==0)path.moveTo(x,y) else path.lineTo(x,y) }
            drawPath(path,accent,style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
        }
    }
}
@Composable fun WifiDial(score:Int?,modifier:Modifier=Modifier) {
    val outline=MaterialTheme.colorScheme.outline; val needle=MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(Dimensions.gaugeHeight).semantics{contentDescription="Qualidade Wi-Fi ${score?.toString()?:"indisponível"} de 100"}) {
        val r=min(size.width/2-16.dp.toPx(),size.height-25.dp.toPx()); val center=Offset(size.width/2,size.height-12.dp.toPx())
        val rect=Offset(center.x-r,center.y-r); val diam=Size(2*r,2*r)
        val shades=listOf(Palette.Blue,Palette.Aqua,Palette.Teal,Palette.Green,Palette.Lime)
        repeat(5){i->drawArc(shades[i],180f+i*36,32f,false,rect,diam,style=Stroke(12.dp.toPx(),cap=StrokeCap.Round))}
        repeat(11){i->val a=Math.PI+i*Math.PI/10; val outer=Offset(center.x+(cos(a)*(r-14.dp.toPx())).toFloat(),center.y+(sin(a)*(r-14.dp.toPx())).toFloat()); val inner=Offset(center.x+(cos(a)*(r-22.dp.toPx())).toFloat(),center.y+(sin(a)*(r-22.dp.toPx())).toFloat());drawLine(outline,inner,outer,2f)}
        if(score!=null){val a=Math.PI+score.coerceIn(0,100)*Math.PI/100;drawLine(needle,center,Offset(center.x+(cos(a)*(r-34.dp.toPx())).toFloat(),center.y+(sin(a)*(r-34.dp.toPx())).toFloat()),4.dp.toPx(),StrokeCap.Round)}
        drawCircle(needle,7.dp.toPx(),center)
    }
}
@Composable fun Bubble(x:Double,y:Double=0.0,vertical:Boolean=false,plane:Boolean=false,modifier:Modifier=Modifier) {
    val visualX by animateFloatAsState(x.toFloat(),tween(120),label="bolha X")
    val visualY by animateFloatAsState(y.toFloat(),tween(120),label="bolha Y")
    val accent=MaterialTheme.colorScheme.primary; val rim=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.85f); val fill=MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.semantics{contentDescription=if(plane)"Nível de superfície" else if(vertical)"Ampola vertical Y" else "Ampola horizontal X"}) {
        val c=Offset(size.width/2,size.height/2); val rad=min(size.width,size.height)/2-2.dp.toPx()
        if(plane){drawCircle(fill,rad,c);drawCircle(rim,rad,c,style=Stroke(2.dp.toPx()));drawCircle(rim,rad*.3f,c,style=Stroke(1.dp.toPx()));drawLine(rim,Offset(c.x-rad,c.y),Offset(c.x+rad,c.y));drawLine(rim,Offset(c.x,c.y-rad),Offset(c.x,c.y+rad));var dx=(sin(Math.toRadians(visualX.toDouble()))*rad).toFloat();var dy=(-sin(Math.toRadians(visualY.toDouble()))*rad).toFloat();val len=hypot(dx,dy);if(len>rad*.8f){dx*=rad*.8f/len;dy*=rad*.8f/len};drawCircle(accent,rad*.14f,Offset(c.x+dx,c.y+dy))}
        else {drawRoundRect(fill,cornerRadius=androidx.compose.ui.geometry.CornerRadius(rad,rad));drawRoundRect(rim,cornerRadius=androidx.compose.ui.geometry.CornerRadius(rad,rad),style=Stroke(2.dp.toPx()));val d=sin(Math.toRadians(visualX.toDouble()))*if(vertical)size.height*.4 else size.width*.4;val pos=if(vertical)Offset(c.x,c.y-d.toFloat())else Offset(c.x+d.toFloat(),c.y);drawCircle(accent,rad*.55f,pos)
            for(sign in listOf(-1,1)){if(vertical){val q=c.y+sign*rad*.6f;drawLine(rim,Offset(0f,q),Offset(size.width,q),2.dp.toPx())}else{val q=c.x+sign*rad*.6f;drawLine(rim,Offset(q,0f),Offset(q,size.height),2.dp.toPx())}}
        }
    }
}
@Composable fun Horizon(pitch:Double,roll:Double) {
    val accent=MaterialTheme.colorScheme.primary;val rim=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.85f);val fill=MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(Dimensions.horizonHeight).semantics{contentDescription="Horizonte: X ${AngleDisplay.format(pitch)}, Y ${AngleDisplay.format(roll)}"}) {
        val c=Offset(size.width/2,size.height/2);val r=size.height*.46f;drawCircle(fill,r,c);drawCircle(rim,r,c,style=Stroke(2.dp.toPx()));drawLine(rim,Offset(c.x-r,c.y),Offset(c.x+r,c.y));val a=Math.toRadians(roll);val dy=(pitch.coerceIn(-45.0,45.0)/45*r*.5).toFloat();drawLine(accent,Offset(c.x-(cos(a)*r*.85).toFloat(),c.y-dy-(sin(a)*r*.85).toFloat()),Offset(c.x+(cos(a)*r*.85).toFloat(),c.y-dy+(sin(a)*r*.85).toFloat()),3.dp.toPx(),StrokeCap.Round);drawCircle(accent,4.dp.toPx(),c)
    }
}
