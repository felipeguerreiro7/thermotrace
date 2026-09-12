package com.thermotrace.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thermotrace.app.ui.theme.CianoTrace
import com.thermotrace.app.ui.theme.IndigoPulse
import com.thermotrace.app.R

/** Fundo discreto de instrumentação, compartilhado por todas as rotas. */
@Composable
fun TechBackdrop(content: @Composable BoxScope.() -> Unit) {
    val grade = MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)
    val halo = MaterialTheme.colorScheme.secondary.copy(alpha = 0.11f)
    // Um Box com background nao fornece LocalContentColor. Sem esta camada,
    // Text sem cor explicita herda preto, mesmo dentro do tema escuro.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
                .drawBehind {
                    drawCircle(
                        color = halo,
                        radius = size.minDimension * 0.62f,
                        center = Offset(size.width * 1.04f, size.height * 0.08f),
                    )
                    val passo = 30.dp.toPx()
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(grade, Offset(x, 0f), Offset(x, size.height), 1f)
                        x += passo
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(grade, Offset(0f, y), Offset(size.width, y), 1f)
                        y += passo
                    }
                },
            content = content,
        )
    }
}

/** Marca compacta: rota térmica monitorada dentro de uma célula industrial. */
@Composable
fun ThermoTraceMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
                shape = RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.thermotrace_mark),
            contentDescription = "ThermoTrace",
            modifier = Modifier.size(35.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/** Barra comum a todas as telas para que o app pareça um único instrumento. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechTopAppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    branded: Boolean = false,
    eyebrow: String = "THERMOTRACE  /  OPERATIONS",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                        MaterialTheme.colorScheme.surface,
                    )
                )
            )
    ) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (branded) {
                        ThermoTraceMark()
                        Spacer(Modifier.width(11.dp))
                    }
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = title,
                            style = if (branded) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (branded) "COLD CHAIN INTELLIGENCE" else eyebrow,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.15.sp,
                            maxLines = 1,
                        )
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
                navigationIconContentColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}
