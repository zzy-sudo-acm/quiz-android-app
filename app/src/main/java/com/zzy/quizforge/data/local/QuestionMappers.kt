package com.zzy.quizforge.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zzy.quizforge.data.local.entity.QuestionEntity
import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion

private val gson = Gson()
private val optionListType = object : TypeToken<List<QuestionOption>>() {}.type
private val stringListType = object : TypeToken<List<String>>() {}.type

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
    )

fun QuestionEntity.toDomain(): QuizQuestion =
    QuizQuestion(
        id = id,
        bankId = bankId,
        originalId = originalId,
        type = QuestionType.fromRaw(type),
        question = question,
        options = gson.fromJson(optionsJson, optionListType),
        answer = gson.fromJson<List<String>>(answerJson, stringListType).map { it.uppercase() }.sorted(),
        explanation = explanation,
        knowledge = knowledge,
        image = image,
        imageUri = imageUri,
    )

fun answersEqual(userAnswer: Collection<String>, correctAnswer: Collection<String>): Boolean =
    userAnswer.map { it.uppercase() }.sorted() == correctAnswer.map { it.uppercase() }.sorted()
