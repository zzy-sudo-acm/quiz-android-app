package com.zzy.quizforge

import android.app.Application
import android.util.Log
import com.zzy.quizforge.data.local.AppDatabase
import com.zzy.quizforge.data.remote.DeepSeekApi
import com.zzy.quizforge.data.repository.ImportRepository
import com.zzy.quizforge.data.repository.QuizRepository
import com.zzy.quizforge.data.repository.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class QuizForgeApplication : Application() {
    lateinit var database: AppDatabase
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var quizRepository: QuizRepository
        private set

    lateinit var importRepository: ImportRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        settingsStore = SettingsStore(this)
        quizRepository = QuizRepository(database, assets, filesDir)

        val streamingClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val repairClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        importRepository = ImportRepository(
            context = this,
            api = DeepSeekApi(streamingClient, repairClient),
            quizRepository = quizRepository,
            settingsStore = settingsStore,
        )

        // Snapshot before the UI can create a new task. Background cleanup therefore cannot
        // remove an import started in this process, even if the user opens the screen immediately.
        val staleImportTaskNames = File(filesDir, "import-temp").listFiles().orEmpty().map { it.name }
        appScope.launch {
            runCatching { quizRepository.clearStaleImportTasks(staleImportTaskNames) }
                .onFailure { Log.w("QuizForgeApplication", "上次遗留的导入临时目录未能完全清理", it) }
            quizRepository.seedDefaultBankIfNeeded()
        }
    }
}
