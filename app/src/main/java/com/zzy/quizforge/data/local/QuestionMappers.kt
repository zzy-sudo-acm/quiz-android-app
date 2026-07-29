package com.zzy.quizforge.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zzy.quizforge.data.local.entity.QuestionEntity
import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion

private val gson = Gson()
private val storedOptionListType = object : TypeToken<List<StoredQuestionOption?>>() {}.type
private val stringListType = object : TypeToken<List<String?>>() {}.type

private data class StoredQuestionOption(
    val key: String? = null,
    val text: String? = null,
    val image: String? = null,
    val imageUri: String? = null,
    val imageUris: List<String?>? = null,
)

fun QuizQuestion.toEntity(bankId: Long): QuestionEntity =
    QuestionEntity(
        bankId = bankId,
        originalId = originalId,
        type = type.dbValue,
        question = question,
        optionsJson = gson.toJson(options),
        answerJson = gson.toJson(answer.map { it.uppercase() }.sorted()),
        explanation = explanation,
        knowledge = knowledge,
        image = image,
        imageUri = imageUri,
        imageUrisJson = gson.toJson(imageUris),
    )

fun QuestionEntity.toDomain(): QuizQuestion =
    QuizQuestion(
        id = id,
        bankId = bankId,
        originalId = originalId,
        type = QuestionType.fromRaw(type),
        question = question,
        options = gson.fromJson<List<StoredQuestionOption?>>(optionsJson, storedOptionListType)
            .orEmpty()
            .mapNotNull { stored ->
                stored ?: return@mapNotNull null
                QuestionOption(
                    key = stored.key.orEmpty(),
                    text = stored.text.orEmpty(),
                    image = stored.image,
                    imageUri = stored.imageUri,
                    imageUris = stored.imageUris.orEmpty().filterNotNull().filter(String::isNotBlank)
                        .ifEmpty { listOfNotNull(stored.imageUri) },
                )
            },
        answer = gson.fromJson<List<String?>>(answerJson, stringListType)
            .orEmpty().filterNotNull().map { it.uppercase() }.sorted(),
        explanation = explanation,
        knowledge = knowledge,
        image = image,
        imageUri = imageUri,
        imageUris = runCatching {
            gson.fromJson<List<String?>>(imageUrisJson, stringListType)
                .orEmpty().filterNotNull().filter(String::isNotBlank)
        }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(imageUri) },
    )

fun answersEqual(userAnswer: Collection<String>, correctAnswer: Collection<String>): Boolean =
    userAnswer.map { it.uppercase() }.sorted() == correctAnswer.map { it.uppercase() }.sorted()
