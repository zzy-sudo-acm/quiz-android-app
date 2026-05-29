package com.zzy.quizforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.ui.home.HomeScreen
import com.zzy.quizforge.ui.importdoc.ImportScreen
import com.zzy.quizforge.ui.quiz.QuizScreen
import com.zzy.quizforge.ui.settings.SettingsScreen
import com.zzy.quizforge.ui.theme.QuizForgeTheme
import com.zzy.quizforge.ui.viewmodel.HomeViewModelFactory
import com.zzy.quizforge.ui.viewmodel.ImportViewModelFactory
import com.zzy.quizforge.ui.viewmodel.QuizViewModelFactory
import com.zzy.quizforge.ui.viewmodel.SettingsViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as QuizForgeApplication
        setContent {
            QuizForgeTheme {
                QuizForgeNav(app = app)
            }
        }
    }
}

@Composable
private fun QuizForgeNav(app: QuizForgeApplication) {
    val navController = rememberNavController()

    // 统一的过渡参数：消除"立刻闪现"造成的肉眼卡顿感
    val durationForward = 260
    val durationBack = 220
    val slideOffset: (Int) -> Int = { it / 6 }

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier,
        enterTransition = {
            slideInHorizontally(animationSpec = tween(durationForward), initialOffsetX = slideOffset) +
                fadeIn(animationSpec = tween(durationForward))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(durationForward / 2))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(durationBack))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(durationBack), targetOffsetX = slideOffset) +
                fadeOut(animationSpec = tween(durationBack))
        },
    ) {
        composable("home") {
            HomeScreen(
                viewModel = viewModel(factory = HomeViewModelFactory(app.quizRepository)),
                onImport = { navController.navigate("import") },
                onSettings = { navController.navigate("settings") },
                onStartQuiz = { bankId, mode ->
                    navController.navigate("quiz/$bankId/${mode.routeValue}")
                },
            )
        }
        composable(
            route = "quiz/{bankId}/{mode}",
            arguments = listOf(
                navArgument("bankId") { type = NavType.LongType },
                navArgument("mode") { type = NavType.StringType },
            ),
        ) { entry ->
            val bankId = entry.arguments?.getLong("bankId") ?: 0L
            val mode = QuizMode.fromRoute(entry.arguments?.getString("mode"))
            QuizScreen(
                viewModel = viewModel(
                    key = "quiz-$bankId-${mode.routeValue}",
                    factory = QuizViewModelFactory(app.quizRepository, bankId, mode),
                ),
                onBack = { navController.popBackStack() },
            )
        }
        composable("import") {
            ImportScreen(
                viewModel = viewModel(factory = ImportViewModelFactory(app.importRepository)),
                onBack = { navController.popBackStack() },
                onOpenBank = { bankId ->
                    navController.navigate("quiz/$bankId/${QuizMode.SEQUENTIAL.routeValue}") {
                        popUpTo("home")
                    }
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel(
                    factory = SettingsViewModelFactory(
                        settingsStore = app.settingsStore,
                        quizRepository = app.quizRepository,
                    ),
                ),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
