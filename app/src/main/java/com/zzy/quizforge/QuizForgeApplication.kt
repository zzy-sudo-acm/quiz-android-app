package com.zzy.quizforge

import android.app.Application
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
        quizRepository = QuizRepository(database, assets)

        val streamingClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        importRepository = ImportRepository(
            context = this,
            api = DeepSeekApi(streamingClient),
            quizRepository = quizRepository,
            settingsStore = settingsStore,
        )

        appScope.launch {
            quizRepository.seedDefaultBankIfNeeded()
        }
    }
}
