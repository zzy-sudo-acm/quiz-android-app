package com.zzy.quizforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zzy.quizforge.data.repository.ImportRepository
import com.zzy.quizforge.data.repository.QuizRepository
import com.zzy.quizforge.data.repository.SettingsStore
import com.zzy.quizforge.domain.model.QuizMode
import com.zzy.quizforge.ui.home.HomeViewModel
import com.zzy.quizforge.ui.importdoc.ImportViewModel
import com.zzy.quizforge.ui.quiz.QuizViewModel
import com.zzy.quizforge.ui.settings.SettingsViewModel

class HomeViewModelFactory(
    private val repository: QuizRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HomeViewModel(repository) as T
    }
}

class QuizViewModelFactory(
    private val repository: QuizRepository,
    private val bankId: Long,
    private val mode: QuizMode,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return QuizViewModel(repository, bankId, mode) as T
    }
}

class ImportViewModelFactory(
    private val repository: ImportRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ImportViewModel(repository) as T
    }
}

class SettingsViewModelFactory(
    private val settingsStore: SettingsStore,
    private val quizRepository: QuizRepository,
    private val importRepository: ImportRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(settingsStore, quizRepository, importRepository) as T
    }
}
