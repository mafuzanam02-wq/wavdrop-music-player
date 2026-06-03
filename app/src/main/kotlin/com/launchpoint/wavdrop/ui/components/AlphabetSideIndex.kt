package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Vertical A-Z fast-scroll index shown on the trailing edge of long lists.
 *
 * @param activeLetter Letter highlighted to match the first visible section in the list.
 *                     Pass null when the current section is unknown.
 * @param onLetterSelected Called on tap AND during drag as the finger moves over letters.
 */
@Composable
fun AlphabetSideIndex(
    activeLetter: Char? = null,
    onLetterSelected: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val letters = AlphabetLetters
    var draggedLetter by remember { mutableStateOf<Char?>(null) }

    Box(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight(),
    ) {
        // Floating letter bubble: appears to the left of the 28 dp strip while touching.
        // Negative X offset places it over the list content; Compose does not clip by default.
        draggedLetter?.let { letter ->
            DragLetterBubble(
                letter = letter,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-60).dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 6.dp)
                .pointerInput(Unit) {
                    fun letterAt(y: Float): Char {
                        val index = ((y / size.height.toFloat()) * letters.size)
                            .toInt()
                            .coerceIn(0, letters.lastIndex)
                        return letters[index]
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastLetter = letterAt(down.position.y)
                        draggedLetter = lastLetter
                        onLetterSelected(lastLetter)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val newLetter = letterAt(change.position.y)
                            if (newLetter != lastLetter) {
                                lastLetter = newLetter
                                draggedLetter = newLetter
                                onLetterSelected(newLetter)
                            }
                        } while (true)
                        draggedLetter = null
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            letters.forEach { letter ->
                val isActive = letter == activeLetter
                Text(
                    text = letter.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(28.dp)
                        .heightIn(min = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun DragLetterBubble(
    letter: Char,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val AlphabetLetters = listOf('#') + ('A'..'Z').toList()
