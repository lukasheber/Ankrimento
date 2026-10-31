// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.databinding.ViewAnswerAreaBinding

class AnswerAreaView : FrameLayout {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val binding = ViewAnswerAreaBinding.inflate(LayoutInflater.from(context), this)

    fun setButtonListeners(
        onRatingClicked: (Rating) -> Unit,
        onShowAnswerClicked: () -> Unit,
    ) {
        binding.showAnswerButton.setOnClickListener { onShowAnswerClicked() }
        binding.againButton.setOnClickListener { onRatingClicked(Rating.AGAIN) }
        binding.hardButton.setOnClickListener { onRatingClicked(Rating.HARD) }
        binding.goodButton.setOnClickListener { onRatingClicked(Rating.GOOD) }
        binding.easyButton.setOnClickListener { onRatingClicked(Rating.EASY) }
    }

    private var nextTimes: AnswerButtonsNextTime? = null
    private var incrementoButtons: List<Ankrimento.AnswerButton>? = null
    private var hardAndEasyHidden = false

    fun setNextTimes(times: AnswerButtonsNextTime?) {
        nextTimes = times
        renderButtons()
    }

    /** Incremento's buttons for the card (More/Same/Less, Fail/Pass), or null for AnkiDroid's. */
    fun setIncrementoButtons(buttons: List<Ankrimento.AnswerButton>?) {
        incrementoButtons = buttons
        renderButtons()
    }

    private fun renderButtons() {
        val times = nextTimes
        val ankiTimes = listOf(times?.again, times?.hard, times?.good, times?.easy)
        val views = listOf(binding.againButton, binding.hardButton, binding.goodButton, binding.easyButton)
        views.forEachIndexed { index, view ->
            val incremento = incrementoButtons
            val button = incremento?.firstOrNull { it.rating.number == index }
            view.label = button?.label
            view.visibility =
                when {
                    incremento != null -> if (button != null) VISIBLE else GONE
                    hardAndEasyHidden && (index == 1 || index == 3) -> GONE
                    else -> VISIBLE
                }
            val time = if (button != null) button.due ?: ankiTimes.getOrNull(button.ease - 1) else ankiTimes[index]
            view.setNextTime(if (times == null) null else time)
        }
    }

    fun setRelativeHeight(buttonsHeightPercentage: Int) {
        if (buttonsHeightPercentage <= 100) return
        binding.answerButtonsLayout.post {
            binding.answerButtonsLayout.updateLayoutParams {
                height = binding.answerButtonsLayout.measuredHeight * buttonsHeightPercentage / 100
            }
        }
    }

    fun setAnswerState(isAnswerShown: Boolean) {
        if (isAnswerShown) {
            binding.showAnswerButton.visibility = INVISIBLE
            binding.answerButtonsLayout.visibility = VISIBLE
        } else {
            binding.showAnswerButton.visibility = VISIBLE
            binding.answerButtonsLayout.visibility = INVISIBLE
        }
    }

    fun hideHardAndEasyButtons() {
        hardAndEasyHidden = true
        renderButtons()
    }
}
