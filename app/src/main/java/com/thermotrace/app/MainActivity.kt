package com.thermotrace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.thermotrace.app.data.repo.Repositorio
import com.thermotrace.app.data.repo.RepositorioAlertas
import com.thermotrace.app.domain.TipoLeitura
import com.thermotrace.app.ui.screens.AjustesScreen
import com.thermotrace.app.ui.screens.AjustesViewModel
import com.thermotrace.app.ui.screens.DiagnosticoScreen
import com.thermotrace.app.ui.screens.DiagnosticoViewModel
import com.thermotrace.app.ui.screens.EtiquetasScreen
import com.thermotrace.app.ui.screens.EtiquetasViewModel
import com.thermotrace.app.ui.screens.HomeScreen
import com.thermotrace.app.ui.screens.HomeViewModel
import com.thermotrace.app.ui.screens.LeituraScreen
import com.thermotrace.app.ui.screens.LeituraViewModel
import com.thermotrace.app.ui.screens.NovaRemessaScreen
import com.thermotrace.app.ui.screens.OcorrenciaScreen
import com.thermotrace.app.ui.screens.OcorrenciaViewModel
import com.thermotrace.app.ui.screens.OcorrenciasScreen
import com.thermotrace.app.ui.screens.OcorrenciasViewModel
import com.thermotrace.app.ui.screens.NovaRemessaViewModel
import com.thermotrace.app.ui.screens.RelatorioScreen
import com.thermotrace.app.ui.screens.RelatorioViewModel
import com.thermotrace.app.ui.screens.RemessaScreen
import com.thermotrace.app.ui.screens.RemessaViewModel
import com.thermotrace.app.ui.screens.TutorialScreen
import com.thermotrace.app.ui.theme.TemaThermoTrace
import com.thermotrace.app.ui.components.TechBackdrop
import com.thermotrace.app.ui.screens.ContaScreen
import com.thermotrace.app.ui.screens.ContaViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ThermoTraceApp
        setContent {
            val escopo by app.conta.escopo.collectAsStateWithLifecycle()
            key(escopo.chave) {
                val dados = remember { app.abrirDados(escopo) }
                val owner = remember { object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                } }
                DisposableEffect(dados) {
                    onDispose {
                        owner.viewModelStore.clear()
                        // Fecha somente o banco antigo; não bloqueia a UI esperando operações de disco.
                        CoroutineScope(Dispatchers.IO).launch { dados.db.close() }
                    }
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalDadosLocais provides dados) {
                    TemaThermoTrace { TechBackdrop { Navegacao(app, dados) } }
                }
            }
        }
    }
}

/**
 * Fábrica única de ViewModels.
 *
 * O grafo é pequeno o bastante para não justificar Hilt: uma dependência a
 * menos para configurar, e o build fica mais rápido no ciclo de campo, que
 * é onde este app é testado de verdade.
 */
val LocalDadosLocais = staticCompositionLocalOf<DadosLocais?> { null }

private class Fabrica(private val app: ThermoTraceApp, private val dados: DadosLocais) : ViewModelProvider.Factory {
    private val repo: Repositorio get() = dados.repositorio
    private val alertas: RepositorioAlertas get() = dados.alertas

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ContaViewModel::class.java) -> ContaViewModel(app.conta)
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(repo, alertas, dados.preferencias, dados.fluxo)
        modelClass.isAssignableFrom(NovaRemessaViewModel::class.java) -> NovaRemessaViewModel(repo)
        modelClass.isAssignableFrom(RemessaViewModel::class.java) -> RemessaViewModel(repo, alertas)
        modelClass.isAssignableFrom(LeituraViewModel::class.java) ->
            LeituraViewModel(repo, alertas, dados.preferencias, dados.fluxo,
                com.thermotrace.app.data.local.Localizador(app))
        modelClass.isAssignableFrom(RelatorioViewModel::class.java) -> RelatorioViewModel(repo)
        modelClass.isAssignableFrom(EtiquetasViewModel::class.java) -> EtiquetasViewModel(repo)
        modelClass.isAssignableFrom(OcorrenciaViewModel::class.java) ->
            OcorrenciaViewModel(alertas, false)
        modelClass.isAssignableFrom(OcorrenciasViewModel::class.java) ->
            OcorrenciasViewModel(alertas, null) { url -> dados.preferencias.definirUrlServidor(url) }
        modelClass.isAssignableFrom(DiagnosticoViewModel::class.java) -> DiagnosticoViewModel()
        modelClass.isAssignableFrom(AjustesViewModel::class.java) ->
            AjustesViewModel(dados.preferencias, app.installId, app.versaoLegivel())
        else -> error("ViewModel desconhecido: ${modelClass.name}")
    } as T
}

object Rotas {
    const val CONTA = "conta"
    const val HOME = "home"
    const val NOVA_REMESSA = "nova-remessa"
    const val ETIQUETAS = "etiquetas"
    const val REMESSA = "remessa/{remessaId}"
    const val RELATORIO = "relatorio/{remessaId}"
    const val LEITURA = "leitura/{volumeId}/{tipo}"
    const val OCORRENCIAS = "ocorrencias"
    const val OCORRENCIA = "ocorrencia/{ocorrenciaId}"
    const val AJUSTES = "ajustes"
    const val DIAGNOSTICO = "diagnostico"
    const val TUTORIAL = "tutorial"

    fun remessa(id: String) = "remessa/$id"
    fun relatorio(id: String) = "relatorio/$id"
    fun leitura(volumeId: String, tipo: TipoLeitura) = "leitura/$volumeId/${tipo.name}"
    fun ocorrencia(id: String) = "ocorrencia/$id"
}

@Composable
fun Navegacao(app: ThermoTraceApp, dados: DadosLocais) {
    val nav = rememberNavController()
    val fabrica = Fabrica(app, dados)

    // Deslizar para a esquerda ao entrar, para a direita ao voltar: dá
    // direção ao fluxo. Num app operado com uma mão só, de luva, saber se
    // você avançou ou voltou pelo movimento vale mais que pelo título.
    val duracao = 220

    NavHost(
        navController = nav,
        startDestination = if (dados.preferencias.atual.tutorialConcluido || dados.escopo.vinculado)
            Rotas.HOME else Rotas.TUTORIAL,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left, tween(duracao)
            ) + fadeIn(tween(duracao))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left, tween(duracao)
            ) + fadeOut(tween(duracao))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right, tween(duracao)
            ) + fadeIn(tween(duracao))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right, tween(duracao)
            ) + fadeOut(tween(duracao))
        },
    ) {
        composable(Rotas.CONTA) {
            ContaScreen(vm = viewModel(factory = fabrica), aoVoltar = { nav.popBackStack() })
        }

        composable(Rotas.HOME) {
            HomeScreen(
                vm = viewModel(factory = fabrica),
                aoAbrirRemessa = { nav.navigate(Rotas.remessa(it)) },
                aoNovaRemessa = { nav.navigate(Rotas.NOVA_REMESSA) },
                aoAbrirEtiquetas = { nav.navigate(Rotas.ETIQUETAS) },
                aoAbrirOcorrencias = { nav.navigate(Rotas.OCORRENCIAS) },
                aoAbrirAjustes = { nav.navigate(Rotas.AJUSTES) },
                aoAbrirDiagnostico = { nav.navigate(Rotas.DIAGNOSTICO) },
                aoAbrirTutorial = { nav.navigate(Rotas.TUTORIAL) },
                aoAbrirLaudo = { nav.navigate(Rotas.relatorio(it)) },
                aoAbrirConta = { nav.navigate(Rotas.CONTA) },
                contaVinculada = dados.escopo.vinculado,
                aoLeituraFinal = { nav.navigate(Rotas.leitura(it, TipoLeitura.FINAL)) },
            )
        }

        composable(Rotas.AJUSTES) {
            AjustesScreen(
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
                aoAbrirDiagnostico = { nav.navigate(Rotas.DIAGNOSTICO) },
                aoAbrirTutorial = { nav.navigate(Rotas.TUTORIAL) },
            )
        }

        composable(Rotas.TUTORIAL) {
            val primeiroAcesso = !dados.preferencias.atual.tutorialConcluido
            TutorialScreen(
                primeiroAcesso = primeiroAcesso,
                aoVoltar = { nav.popBackStack() },
                aoConcluir = {
                    dados.preferencias.concluirTutorial()
                    if (primeiroAcesso) {
                        nav.navigate(Rotas.HOME) {
                            popUpTo(Rotas.TUTORIAL) { inclusive = true }
                        }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }

        composable(Rotas.DIAGNOSTICO) {
            DiagnosticoScreen(
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
            )
        }

        composable(Rotas.NOVA_REMESSA) {
            NovaRemessaScreen(
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
                aoCriar = { id ->
                    nav.popBackStack()
                    nav.navigate(Rotas.remessa(id))
                },
            )
        }

        composable(Rotas.ETIQUETAS) {
            EtiquetasScreen(
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
            )
        }

        composable(
            Rotas.REMESSA,
            arguments = listOf(navArgument("remessaId") { type = NavType.StringType }),
        ) { entrada ->
            val id = entrada.arguments?.getString("remessaId").orEmpty()
            RemessaScreen(
                remessaId = id,
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
                aoLer = { volumeId, tipo -> nav.navigate(Rotas.leitura(volumeId, tipo)) },
                aoAbrirRelatorio = { nav.navigate(Rotas.relatorio(id)) },
                aoAbrirOcorrencia = { oc -> nav.navigate(Rotas.ocorrencia(oc)) },
            )
        }

        composable(
            Rotas.LEITURA,
            arguments = listOf(
                navArgument("volumeId") { type = NavType.StringType },
                navArgument("tipo") { type = NavType.StringType },
            ),
        ) { entrada ->
            val volumeId = entrada.arguments?.getString("volumeId").orEmpty()
            val tipo = runCatching {
                TipoLeitura.valueOf(entrada.arguments?.getString("tipo").orEmpty())
            }.getOrDefault(TipoLeitura.ATIVACAO)

            LeituraScreen(
                volumeId = volumeId,
                tipo = tipo,
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
                aoConcluir = { nav.popBackStack() },
            )
        }

        composable(Rotas.OCORRENCIAS) {
            OcorrenciasScreen(
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
                aoAbrirOcorrencia = { id -> nav.navigate(Rotas.ocorrencia(id)) },
            )
        }

        composable(
            Rotas.OCORRENCIA,
            arguments = listOf(navArgument("ocorrenciaId") { type = NavType.StringType }),
        ) { entrada ->
            OcorrenciaScreen(
                ocorrenciaId = entrada.arguments?.getString("ocorrenciaId").orEmpty(),
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
            )
        }

        composable(
            Rotas.RELATORIO,
            arguments = listOf(navArgument("remessaId") { type = NavType.StringType }),
        ) { entrada ->
            RelatorioScreen(
                remessaId = entrada.arguments?.getString("remessaId").orEmpty(),
                vm = viewModel(factory = fabrica),
                aoVoltar = { nav.popBackStack() },
            )
        }
    }
}
