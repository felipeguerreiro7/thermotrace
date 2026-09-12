package com.thermotrace.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thermotrace.app.ui.components.TechTopAppBar
import com.thermotrace.app.ui.theme.AmbarAlerta
import com.thermotrace.app.ui.theme.CianoTrace

private data class PassoUso(
    val titulo: String,
    val descricao: String,
)

private val passosDeUso = listOf(
    PassoUso(
        "Prepare o celular",
        "Desbloqueie a tela e deixe o NFC ligado. Encoste a parte traseira do " +
            "celular na etiqueta e mantenha os dois parados.",
    ),
    PassoUso(
        "Identifique a etiqueta",
        "Na tela Início, aproxime a etiqueta. O ThermoTrace lê o UID e informa " +
            "se ela está parada ou registrando.",
    ),
    PassoUso(
        "Inicie o monitoramento",
        "Escolha perfil, duração e intervalo. Toque em Ativar etiqueta e mantenha " +
            "encostado até a confirmação. Depois, afaste completamente.",
    ),
    PassoUso(
        "Vincule a nota fiscal",
        "Use Bipar nota fiscal para escanear a DANFE. Isso liga o histórico da " +
            "etiqueta ao documento correto da carga.",
    ),
    PassoUso(
        "Leia no destino",
        "Aproxime novamente, toque em Ler histórico e finalizar e mantenha parado. " +
            "O aplicativo salva as temperaturas e mostra o veredito.",
    ),
    PassoUso(
        "Libere a etiqueta",
        "Após salvar o histórico, afaste e aproxime outra vez. Use Parar registro. " +
            "O STOP separado deixa a etiqueta pronta para um novo ciclo.",
    ),
)

@Composable
fun TutorialScreen(
    primeiroAcesso: Boolean,
    aoVoltar: () -> Unit,
    aoConcluir: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TechTopAppBar(
                title = "Como usar",
                onBack = if (primeiroAcesso) null else aoVoltar,
                eyebrow = "GUIDE  /  NFC WORKFLOW",
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = if (primeiroAcesso) "Bem-vindo ao ThermoTrace" else "Fluxo de operação",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Text(
                    "O aplicativo segue a mesma lógica física do fabricante: uma ação NFC " +
                        "por aproximação.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        contentColor = Color.White,
                    ),
                    border = BorderStroke(1.dp, CianoTrace.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "REGRA DO BIPE",
                            color = CianoTrace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "Terminou uma ação? Afaste a etiqueta totalmente antes da próxima. " +
                                "Não deslize o celular durante a leitura.",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            items(passosDeUso.size) { indice ->
                PassoTutorial(indice + 1, passosDeUso[indice])
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SE NÃO RESPONDER", color = AmbarAlerta, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "Afaste, aguarde um segundo e aproxime novamente. Use somente uma " +
                                "etiqueta por vez e mantenha o celular desbloqueado.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = aoConcluir,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 15.dp),
                ) {
                    Text(
                        if (primeiroAcesso) "Começar a usar" else "Concluir tutorial",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PassoTutorial(numero: Int, passo: PassoUso) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            contentColor = Color.White,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.size(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = CianoTrace,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(numero.toString(), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(passo.titulo, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    passo.descricao,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
